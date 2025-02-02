/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.command

import java.util.Collections

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, when}
import org.mockito.invocation.InvocationOnMock

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.{AnalysisTest, Analyzer, FunctionRegistry, NoSuchTableException, ResolveSessionCatalog}
import org.apache.spark.sql.catalyst.catalog.{InMemoryCatalog, SessionCatalog}
import org.apache.spark.sql.catalyst.expressions.{ArrayTransform, AttributeReference, BooleanLiteral, Cast, CheckOverflowInTableInsert, CreateNamedStruct, EvalMode, GetStructField, IntegerLiteral, LambdaFunction, LongLiteral, MapFromArrays, StringLiteral}
import org.apache.spark.sql.catalyst.expressions.objects.{AssertNotNull, StaticInvoke}
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.catalyst.plans.logical.{Assignment, LogicalPlan, UpdateTable}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.connector.catalog.{CatalogManager, CatalogNotFoundException, CatalogV2Util, Column, ColumnDefaultValue, Identifier, SupportsRowLevelOperations, TableCapability, TableCatalog}
import org.apache.spark.sql.connector.expressions.{LiteralValue, Transform}
import org.apache.spark.sql.execution.datasources.v2.V2SessionCatalog
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.StoreAssignmentPolicy
import org.apache.spark.sql.types.{BooleanType, IntegerType, StructType}

class AlignUpdateAssignmentsSuite extends AnalysisTest {

  private val primitiveTable = {
    val t = mock(classOf[SupportsRowLevelOperations])
    val schema = new StructType()
      .add("i", "INT", nullable = false)
      .add("l", "LONG")
      .add("txt", "STRING")
    when(t.columns()).thenReturn(CatalogV2Util.structTypeToV2Columns(schema))
    when(t.partitioning()).thenReturn(Array.empty[Transform])
    t
  }

  private val nestedStructTable = {
    val t = mock(classOf[SupportsRowLevelOperations])
    val schema = new StructType()
      .add("i", "INT")
      .add(
        "s",
        "STRUCT<n_i: INT NOT NULL, n_s: STRUCT<dn_i: INT NOT NULL, dn_l: LONG>>",
        nullable = false)
      .add("txt", "STRING")
    when(t.columns()).thenReturn(CatalogV2Util.structTypeToV2Columns(schema))
    when(t.partitioning()).thenReturn(Array.empty[Transform])
    t
  }

  private val mapArrayTable = {
    val t = mock(classOf[SupportsRowLevelOperations])
    val schema = new StructType()
      .add("i", "INT")
      .add("a", "ARRAY<STRUCT<i1: INT, i2: INT>>")
      .add("m", "MAP<STRING, STRING>")
      .add("txt", "STRING")
    when(t.columns()).thenReturn(CatalogV2Util.structTypeToV2Columns(schema))
    when(t.partitioning()).thenReturn(Array.empty[Transform])
    t
  }

  private val charVarcharTable = {
    val t = mock(classOf[SupportsRowLevelOperations])
    val schema = new StructType()
      .add("c", "CHAR(5)")
      .add(
        "s",
        "STRUCT<n_i: INT, n_vc: VARCHAR(5)>",
        nullable = false)
      .add(
        "a",
        "ARRAY<STRUCT<n_i: INT, n_vc: VARCHAR(5)>>",
        nullable = false)
      .add(
        "mk",
        "MAP<STRUCT<n_i: INT, n_vc: VARCHAR(5)>, STRING>",
        nullable = false)
      .add(
        "mv",
        "MAP<STRING, STRUCT<n_i: INT, n_vc: VARCHAR(5)>>",
        nullable = false)
    when(t.columns()).thenReturn(CatalogV2Util.structTypeToV2Columns(schema))
    when(t.partitioning()).thenReturn(Array.empty[Transform])
    t
  }

  private val acceptsAnySchemaTable = {
    val t = mock(classOf[SupportsRowLevelOperations])
    val schema = new StructType()
      .add("i", "INT", nullable = false)
      .add("l", "LONG")
      .add("txt", "STRING")
    when(t.columns()).thenReturn(CatalogV2Util.structTypeToV2Columns(schema))
    when(t.partitioning()).thenReturn(Array.empty[Transform])
    when(t.capabilities()).thenReturn(Collections.singleton(TableCapability.ACCEPT_ANY_SCHEMA))
    t
  }

  private val defaultValuesTable = {
    val t = mock(classOf[SupportsRowLevelOperations])
    val iDefault = new ColumnDefaultValue("42", LiteralValue(42, IntegerType))
    when(t.columns()).thenReturn(Array(
      Column.create("b", BooleanType, true, null, null),
      Column.create("i", IntegerType, true, null, iDefault, null)))
    when(t.partitioning()).thenReturn(Array.empty[Transform])
    t
  }

  private val v2Catalog = {
    val newCatalog = mock(classOf[TableCatalog])
    when(newCatalog.loadTable(any())).thenAnswer((invocation: InvocationOnMock) => {
      val ident = invocation.getArgument[Identifier](0)
      ident.name match {
        case "primitive_table" => primitiveTable
        case "nested_struct_table" => nestedStructTable
        case "map_array_table" => mapArrayTable
        case "char_varchar_table" => charVarcharTable
        case "accepts_any_schema_table" => acceptsAnySchemaTable
        case "default_values_table" => defaultValuesTable
        case name => throw new NoSuchTableException(Seq(name))
      }
    })
    when(newCatalog.name()).thenReturn("cat")
    newCatalog
  }

  private val v1SessionCatalog =
    new SessionCatalog(new InMemoryCatalog(), FunctionRegistry.builtin, new SQLConf())

  private val v2SessionCatalog = new V2SessionCatalog(v1SessionCatalog)

  private val catalogManager = {
    val manager = mock(classOf[CatalogManager])
    when(manager.catalog(any())).thenAnswer((invocation: InvocationOnMock) => {
      invocation.getArgument[String](0) match {
        case "testcat" => v2Catalog
        case CatalogManager.SESSION_CATALOG_NAME => v2SessionCatalog
        case name => throw new CatalogNotFoundException(s"No such catalog: $name")
      }
    })
    when(manager.currentCatalog).thenReturn(v2Catalog)
    when(manager.currentNamespace).thenReturn(Array.empty[String])
    when(manager.v1SessionCatalog).thenReturn(v1SessionCatalog)
    when(manager.v2SessionCatalog).thenReturn(v2SessionCatalog)
    manager
  }

  test("align assignments (primitive types)") {
    val sql1 = "UPDATE primitive_table AS t SET t.txt = 'new', t.i = 1"
    parseAndAlignAssignments(sql1) match {
      case Seq(
          Assignment(i: AttributeReference, IntegerLiteral(1)),
          Assignment(l: AttributeReference, lValue: AttributeReference),
          Assignment(txt: AttributeReference, StringLiteral("new"))) =>

        assert(i.name == "i")
        assert(l.name == "l" && l == lValue)
        assert(txt.name == "txt")

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql2 = "UPDATE primitive_table SET l = 10L"
    parseAndAlignAssignments(sql2) match {
      case Seq(
          Assignment(i: AttributeReference, iValue: AttributeReference),
          Assignment(l: AttributeReference, LongLiteral(10L)),
          Assignment(txt: AttributeReference, txtValue: AttributeReference)) =>

        assert(i.name == "i" && i == iValue)
        assert(l.name == "l")
        assert(txt.name == "txt" && txt == txtValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql3 = "UPDATE primitive_table AS t SET t.txt = 'new', t.l = 10L, t.i = -1"
    parseAndAlignAssignments(sql3) match {
      case Seq(
          Assignment(i: AttributeReference, IntegerLiteral(-1)),
          Assignment(l: AttributeReference, LongLiteral(10L)),
          Assignment(txt: AttributeReference, StringLiteral("new"))) =>

        assert(i.name == "i")
        assert(l.name == "l")
        assert(txt.name == "txt")

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }
  }

  test("align assignments (structs)") {
    val sql1 =
      "UPDATE nested_struct_table " +
      "SET s = named_struct('n_s', named_struct('dn_i', 1, 'dn_l', 100L), 'n_i', 1)"
    parseAndAlignAssignments(sql1) match {
      case Seq(
          Assignment(i: AttributeReference, iValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: CreateNamedStruct),
          Assignment(txt: AttributeReference, txtValue: AttributeReference)) =>

        assert(i.name == "i" && i == iValue)

        assert(s.name == "s")
        sValue.children match {
          case Seq(
              StringLiteral("n_i"), GetStructField(_, _, Some("n_i")),
              StringLiteral("n_s"), nsValue: CreateNamedStruct) =>

            nsValue.children match {
              case Seq(
                  StringLiteral("dn_i"), GetStructField(_, _, Some("dn_i")),
                  StringLiteral("dn_l"), GetStructField(_, _, Some("dn_l"))) =>
                // OK

              case nsValueChildren =>
                fail(s"Unexpected children for 's.n_s': $nsValueChildren")
            }

          case sValueChildren =>
            fail(s"Unexpected children for 's': $sValueChildren")
        }

        assert(txt.name == "txt" && txt == txtValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql2 = "UPDATE nested_struct_table SET s.n_s = named_struct('dn_i', 1, 'dn_l', 1L)"
    parseAndAlignAssignments(sql2) match {
      case Seq(
          Assignment(i: AttributeReference, iValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: CreateNamedStruct),
          Assignment(txt: AttributeReference, txtValue: AttributeReference)) =>

        assert(i.name == "i" && i == iValue)

        assert(s.name == "s")
        sValue.children match {
          case Seq(
              StringLiteral("n_i"), GetStructField(_, _, Some("n_i")),
              StringLiteral("n_s"), nsValue: CreateNamedStruct) =>

            nsValue.children match {
              case Seq(
                  StringLiteral("dn_i"), IntegerLiteral(1),
                  StringLiteral("dn_l"), LongLiteral(1L)) =>
                // OK

              case nsValueChildren =>
                fail(s"Unexpected children for 's.n_s': $nsValueChildren")
            }

          case sValueChildren =>
            fail(s"Unexpected children for 's': $sValueChildren")
        }

        assert(txt.name == "txt" && txt == txtValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql3 = "UPDATE nested_struct_table SET s.n_s = named_struct('dn_l', 1L, 'dn_i', 1)"
    parseAndAlignAssignments(sql3) match {
      case Seq(
          Assignment(i: AttributeReference, iValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: CreateNamedStruct),
          Assignment(txt: AttributeReference, txtValue: AttributeReference)) =>

        assert(i.name == "i" && i == iValue)

        assert(s.name == "s")
        sValue.children match {
          case Seq(
              StringLiteral("n_i"), GetStructField(_, _, Some("n_i")),
              StringLiteral("n_s"), nsValue: CreateNamedStruct) =>

            nsValue.children match {
              case Seq(
                  StringLiteral("dn_i"), GetStructField(_, _, Some("dn_i")),
                  StringLiteral("dn_l"), GetStructField(_, _, Some("dn_l"))) =>
                // OK

              case nsValueChildren =>
                fail(s"Unexpected children for 's.n_s': $nsValueChildren")
            }

          case sValueChildren =>
            fail(s"Unexpected children for 's': $sValueChildren")
        }

        assert(txt.name == "txt" && txt == txtValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql4 = "UPDATE nested_struct_table SET s.n_i = 1"
    parseAndAlignAssignments(sql4) match {
      case Seq(
          Assignment(i: AttributeReference, iValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: CreateNamedStruct),
          Assignment(txt: AttributeReference, txtValue: AttributeReference)) =>

        assert(i.name == "i" && i == iValue)

        assert(s.name == "s")
        sValue.children match {
          case Seq(
              StringLiteral("n_i"), IntegerLiteral(1),
              StringLiteral("n_s"), GetStructField(_, _, Some("n_s"))) =>
            // OK

          case sValueChildren =>
            fail(s"Unexpected children for 's': $sValueChildren")
        }

        assert(txt.name == "txt" && txt == txtValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }
  }

  test("align assignments (char and varchar types)") {
    val sql1 = "UPDATE char_varchar_table SET c = 'a'"
    parseAndAlignAssignments(sql1) match {
      case Seq(
          Assignment(c: AttributeReference, cValue: StaticInvoke),
          Assignment(s: AttributeReference, sValue: AttributeReference),
          Assignment(a: AttributeReference, aValue: AttributeReference),
          Assignment(mk: AttributeReference, mkValue: AttributeReference),
          Assignment(mv: AttributeReference, mvValue: AttributeReference)) =>

        assert(c.name == "c")
        assert(cValue.arguments.length == 2)
        assert(cValue.functionName == "charTypeWriteSideCheck")
        assert(s.name == "s" && s == sValue)
        assert(a.name == "a" && a == aValue)
        assert(mk.name == "mk" && mk == mkValue)
        assert(mv.name == "mv" && mv == mvValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql2 = "UPDATE char_varchar_table SET s = named_struct('n_i', 1, 'n_vc', 'a')"
    parseAndAlignAssignments(sql2) match {
      case Seq(
          Assignment(c: AttributeReference, cValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: CreateNamedStruct),
          Assignment(a: AttributeReference, aValue: AttributeReference),
          Assignment(mk: AttributeReference, mkValue: AttributeReference),
          Assignment(mv: AttributeReference, mvValue: AttributeReference)) =>

        assert(c.name == "c" && c == cValue)

        assert(s.name == "s")
        sValue.children match {
          case Seq(
              StringLiteral("n_i"), GetStructField(_, _, Some("n_i")),
              StringLiteral("n_vc"), invoke: StaticInvoke) =>

            assert(invoke.arguments.length == 2)
            assert(invoke.functionName == "varcharTypeWriteSideCheck")

          case sValueChildren =>
            fail(s"Unexpected children for 's': $sValueChildren")
        }

        assert(a.name == "a" && a == aValue)
        assert(mk.name == "mk" && mk == mkValue)
        assert(mv.name == "mv" && mv == mvValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql3 = "UPDATE char_varchar_table SET s.n_vc = 'a'"
    parseAndAlignAssignments(sql3) match {
      case Seq(
          Assignment(c: AttributeReference, cValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: CreateNamedStruct),
          Assignment(a: AttributeReference, aValue: AttributeReference),
          Assignment(mk: AttributeReference, mkValue: AttributeReference),
          Assignment(mv: AttributeReference, mvValue: AttributeReference)) =>

        assert(c.name == "c" && c == cValue)

        assert(s.name == "s")
        sValue.children match {
          case Seq(
              StringLiteral("n_i"), GetStructField(_, _, Some("n_i")),
              StringLiteral("n_vc"), invoke: StaticInvoke) =>

            assert(invoke.arguments.length == 2)
            assert(invoke.functionName == "varcharTypeWriteSideCheck")

          case sValueChildren =>
            fail(s"Unexpected children for 's': $sValueChildren")
        }

        assert(a.name == "a" && a == aValue)
        assert(mk.name == "mk" && mk == mkValue)
        assert(mv.name == "mv" && mv == mvValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql4 = "UPDATE char_varchar_table SET s = named_struct('n_vc', 3, 'n_i', 1)"
    parseAndAlignAssignments(sql4) match {
      case Seq(
          Assignment(c: AttributeReference, cValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: CreateNamedStruct),
          Assignment(a: AttributeReference, aValue: AttributeReference),
          Assignment(mk: AttributeReference, mkValue: AttributeReference),
          Assignment(mv: AttributeReference, mvValue: AttributeReference)) =>

        assert(c.name == "c" && c == cValue)

        assert(s.name == "s")
        sValue.children match {
          case Seq(
              StringLiteral("n_i"), GetStructField(_, _, Some("n_i")),
              StringLiteral("n_vc"), invoke: StaticInvoke) =>

            assert(invoke.arguments.length == 2)
            assert(invoke.functionName == "varcharTypeWriteSideCheck")

          case sValueChildren =>
            fail(s"Unexpected children for 's': $sValueChildren")
        }

        assert(a.name == "a" && a == aValue)
        assert(mk.name == "mk" && mk == mkValue)
        assert(mv.name == "mv" && mv == mvValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql5 = "UPDATE char_varchar_table SET a = array(named_struct('n_i', 1, 'n_vc', 3))"
    parseAndAlignAssignments(sql5) match {
      case Seq(
          Assignment(c: AttributeReference, cValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: AttributeReference),
          Assignment(a: AttributeReference, aValue: ArrayTransform),
          Assignment(mk: AttributeReference, mkValue: AttributeReference),
          Assignment(mv: AttributeReference, mvValue: AttributeReference)) =>

        assert(c.name == "c" && c == cValue)
        assert(s.name == "s" && s == sValue)

        assert(a.name == "a")
        val lambda = aValue.function.asInstanceOf[LambdaFunction]
        lambda.function match {
          case CreateNamedStruct(Seq(
              StringLiteral("n_i"), GetStructField(_, _, Some("n_i")),
              StringLiteral("n_vc"), invoke: StaticInvoke)) =>

            assert(invoke.arguments.length == 2)
            assert(invoke.functionName == "varcharTypeWriteSideCheck")

          case func =>
            fail(s"Unexpected lambda function: $func")
        }

        assert(mk.name == "mk" && mk == mkValue)
        assert(mv.name == "mv" && mv == mvValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql6 = "UPDATE char_varchar_table SET a = array(named_struct('n_vc', 3, 'n_i', 1))"
    parseAndAlignAssignments(sql6) match {
      case Seq(
          Assignment(c: AttributeReference, cValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: AttributeReference),
          Assignment(a: AttributeReference, aValue: ArrayTransform),
          Assignment(mk: AttributeReference, mkValue: AttributeReference),
          Assignment(mv: AttributeReference, mvValue: AttributeReference)) =>

        assert(c.name == "c" && c == cValue)
        assert(s.name == "s" && s == sValue)

        assert(a.name == "a")
        val lambda = aValue.function.asInstanceOf[LambdaFunction]
        lambda.function match {
          case CreateNamedStruct(Seq(
              StringLiteral("n_i"), GetStructField(_, _, Some("n_i")),
              StringLiteral("n_vc"), invoke: StaticInvoke)) =>

            assert(invoke.arguments.length == 2)
            assert(invoke.functionName == "varcharTypeWriteSideCheck")

          case func =>
            fail(s"Unexpected lambda function: $func")
        }

        assert(mk.name == "mk" && mk == mkValue)
        assert(mv.name == "mv" && mv == mvValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql7 = "UPDATE char_varchar_table SET mk = map(named_struct('n_vc', 'a', 'n_i', 1), 'v')"
    parseAndAlignAssignments(sql7) match {
      case Seq(
          Assignment(c: AttributeReference, cValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: AttributeReference),
          Assignment(a: AttributeReference, aValue: AttributeReference),
          Assignment(mk: AttributeReference, mkValue: MapFromArrays),
          Assignment(mv: AttributeReference, mvValue: AttributeReference)) =>

        assert(c.name == "c" && c == cValue)
        assert(s.name == "s" && s == sValue)
        assert(a.name == "a" && a == aValue)

        assert(mk.name == "mk")
        val keyTransform = mkValue.left.asInstanceOf[ArrayTransform]
        val keyLambda = keyTransform.function.asInstanceOf[LambdaFunction]
        keyLambda.function match {
          case CreateNamedStruct(Seq(
              StringLiteral("n_i"), GetStructField(_, _, Some("n_i")),
              StringLiteral("n_vc"), invoke: StaticInvoke)) =>

            assert(invoke.arguments.length == 2)
            assert(invoke.functionName == "varcharTypeWriteSideCheck")

          case func =>
            fail(s"Unexpected key lambda function: $func")
        }

        assert(mv.name == "mv" && mv == mvValue)

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }

    val sql8 = "UPDATE char_varchar_table SET mv = map('v', named_struct('n_vc', 'a', 'n_i', 1))"
    parseAndAlignAssignments(sql8) match {
      case Seq(
          Assignment(c: AttributeReference, cValue: AttributeReference),
          Assignment(s: AttributeReference, sValue: AttributeReference),
          Assignment(a: AttributeReference, aValue: AttributeReference),
          Assignment(mk: AttributeReference, mkValue: AttributeReference),
          Assignment(mv: AttributeReference, mvValue: MapFromArrays)) =>

        assert(c.name == "c" && c == cValue)
        assert(s.name == "s" && s == sValue)
        assert(a.name == "a" && a == aValue)
        assert(mk.name == "mk" && mk == mkValue)

        assert(mv.name == "mv")
        val valueTransform = mvValue.right.asInstanceOf[ArrayTransform]
        val valueLambda = valueTransform.function.asInstanceOf[LambdaFunction]
        valueLambda.function match {
          case CreateNamedStruct(Seq(
              StringLiteral("n_i"), GetStructField(_, _, Some("n_i")),
              StringLiteral("n_vc"), invoke: StaticInvoke)) =>

            assert(invoke.arguments.length == 2)
            assert(invoke.functionName == "varcharTypeWriteSideCheck")

          case func =>
            fail(s"Unexpected key lambda function: $func")
        }

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }
  }

  test("conflicting assignments") {
    Seq(StoreAssignmentPolicy.ANSI, StoreAssignmentPolicy.STRICT).foreach { policy =>
      withSQLConf(SQLConf.STORE_ASSIGNMENT_POLICY.key -> policy.toString) {
        // two updates to a top-level column
        assertAnalysisException(
          "UPDATE primitive_table SET i = 1, l = 1L, i = -1",
          "Multiple assignments for 'i': 1, -1")

        // two updates to a nested column
        assertAnalysisException(
          "UPDATE nested_struct_table SET s.n_i = 1, s.n_s = null, s.n_i = -1",
          "Multiple assignments for 's.n_i': 1, -1")

        // conflicting updates to a nested struct and its fields
        assertAnalysisException(
          "UPDATE nested_struct_table " +
          "SET s.n_s.dn_i = 1, s.n_s = named_struct('dn_i', 1, 'dn_l', 1L)",
          "Conflicting assignments for 's.n_s'",
          "cat.nested_struct_table.s.`n_s` = named_struct('dn_i', 1, 'dn_l', 1L)",
          "cat.nested_struct_table.s.`n_s`.`dn_i` = 1")
      }
    }
  }

  test("updates to nested structs in arrays") {
    Seq(StoreAssignmentPolicy.ANSI, StoreAssignmentPolicy.STRICT).foreach { policy =>
      withSQLConf(SQLConf.STORE_ASSIGNMENT_POLICY.key -> policy.toString) {
        assertAnalysisException(
          "UPDATE map_array_table SET a.i1 = 1",
          "Updating nested fields is only supported for StructType but 'a' is of type ArrayType")
      }
    }
  }

  test("ANSI mode assignments") {
    withSQLConf(SQLConf.STORE_ASSIGNMENT_POLICY.key -> StoreAssignmentPolicy.ANSI.toString) {
      val plan1 = parseAndResolve("UPDATE primitive_table SET i = NULL")
      assertNullCheckExists(plan1, Seq("i"))

      val plan2 = parseAndResolve("UPDATE nested_struct_table SET s.n_i = NULL")
      assertNullCheckExists(plan2, Seq("s", "n_i"))

      val plan3 = parseAndResolve("UPDATE nested_struct_table SET s.n_s.dn_i = NULL")
      assertNullCheckExists(plan3, Seq("s", "n_s", "dn_i"))

      val plan4 = parseAndResolve(
        "UPDATE nested_struct_table SET s.n_s = named_struct('dn_i', NULL, 'dn_l', 1L)")
      assertNullCheckExists(plan4, Seq("s", "n_s", "dn_i"))

      assertAnalysisException(
        "UPDATE nested_struct_table SET s.n_s = named_struct('dn_i', 1)",
        "Cannot find data for output column 's.n_s.dn_l'")

      // ANSI mode does NOT allow string to int casts
      assertAnalysisException(
        "UPDATE nested_struct_table SET s.n_s = named_struct('dn_i', 'string-value', 'dn_l', 1L)",
        "Cannot safely cast")

      // ANSI mode allows long to int casts
      val validSql1 = "UPDATE primitive_table SET i = 1L, txt = 'new', l = 10L"
      parseAndAlignAssignments(validSql1) match {
        case Seq(
            Assignment(
                i: AttributeReference,
                CheckOverflowInTableInsert(
                    Cast(LongLiteral(1L), IntegerType, _, EvalMode.ANSI), _)),
            Assignment(l: AttributeReference, LongLiteral(10L)),
            Assignment(txt: AttributeReference, StringLiteral("new"))) =>

          assert(i.name == "i")
          assert(l.name == "l")
          assert(txt.name == "txt")

        case assignments =>
          fail(s"Unexpected assignments: $assignments")
      }
    }
  }

  test("strict mode assignments") {
    withSQLConf(SQLConf.STORE_ASSIGNMENT_POLICY.key -> StoreAssignmentPolicy.STRICT.toString) {
      val plan1 = parseAndResolve("UPDATE primitive_table SET i = CAST(NULL AS INT)")
      assertNullCheckExists(plan1, Seq("i"))

      val plan2 = parseAndResolve("UPDATE nested_struct_table SET s.n_i = CAST(NULL AS INT)")
      assertNullCheckExists(plan2, Seq("s", "n_i"))

      val plan3 = parseAndResolve("UPDATE nested_struct_table SET s.n_s.dn_i = CAST(NULL AS INT)")
      assertNullCheckExists(plan3, Seq("s", "n_s", "dn_i"))

      val plan4 = parseAndResolve(
        """UPDATE nested_struct_table
          |SET s.n_s = named_struct('dn_i', CAST (NULL AS INT), 'dn_l', 1L)""".stripMargin)
      assertNullCheckExists(plan4, Seq("s", "n_s", "dn_i"))

      assertAnalysisException(
        "UPDATE nested_struct_table SET s.n_s = named_struct('dn_i', 1)",
        "Cannot find data for output column 's.n_s.dn_l'")

      // strict mode does NOT allow string to int casts
      assertAnalysisException(
        "UPDATE nested_struct_table SET s.n_s = named_struct('dn_i', 'string-value', 'dn_l', 1L)",
        "Cannot safely cast")

      // strict mode does not allow long to int casts
      assertAnalysisException(
        "UPDATE primitive_table SET i = 1L",
        "Cannot safely cast")
    }
  }

  test("legacy mode assignments") {
    withSQLConf(SQLConf.STORE_ASSIGNMENT_POLICY.key -> StoreAssignmentPolicy.LEGACY.toString) {
      assertAnalysisException(
        "UPDATE nested_struct_table SET s.n_s = named_struct('dn_i', 1)",
        "LEGACY store assignment policy is disallowed in Spark data source V2")
    }
  }

  test("skip alignment for tables that accept any schema") {
    val sql = "UPDATE accepts_any_schema_table SET txt = 'new', i = 1"
    parseAndAlignAssignments(sql) match {
      case Seq(
          Assignment(txt: AttributeReference, StringLiteral("new")),
          Assignment(i: AttributeReference, IntegerLiteral(1))) =>

        assert(i.name == "i")
        assert(txt.name == "txt")

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }
  }

  test("align assignments with default values") {
    val sql = "UPDATE default_values_table SET i = DEFAULT, b = false"
    parseAndAlignAssignments(sql) match {
      case Seq(
          Assignment(b: AttributeReference, BooleanLiteral(false)),
          Assignment(i: AttributeReference, IntegerLiteral(42))) =>

        assert(b.name == "b")
        assert(i.name == "i")

      case assignments =>
        fail(s"Unexpected assignments: $assignments")
    }
  }

  private def parseAndAlignAssignments(query: String): Seq[Assignment] = {
    parseAndResolve(query) match {
      case UpdateTable(_, assignments, _) => assignments
      case plan => fail("Expected UpdateTable, but got:\n" + plan.treeString)
    }
  }

  private def parseAndResolve(query: String): LogicalPlan = {
    val analyzer = new Analyzer(catalogManager) {
      override val extendedResolutionRules: Seq[Rule[LogicalPlan]] = Seq(
        new ResolveSessionCatalog(catalogManager))
    }
    val analyzed = analyzer.execute(CatalystSqlParser.parsePlan(query))
    analyzer.checkAnalysis(analyzed)
    analyzed
  }

  private def assertAnalysisException(query: String, messages: String*): Unit = {
    val exception = intercept[AnalysisException] {
      parseAndResolve(query)
    }
    messages.foreach(message => assert(exception.message.contains(message)))
  }

  private def assertNullCheckExists(plan: LogicalPlan, colPath: Seq[String]): Unit = {
    val asserts = plan.expressions.flatMap(e => e.collect {
      case assert: AssertNotNull if assert.walkedTypePath == colPath => assert
    })
    assert(asserts.nonEmpty, s"Must have NOT NULL checks for col $colPath")
  }
}
