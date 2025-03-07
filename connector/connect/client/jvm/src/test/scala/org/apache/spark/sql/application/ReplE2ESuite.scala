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
package org.apache.spark.sql.application

import java.io.{PipedInputStream, PipedOutputStream}
import java.nio.file.Paths
import java.util.concurrent.{Executors, Semaphore, TimeUnit}

import scala.util.Properties

import org.apache.commons.io.output.ByteArrayOutputStream
import org.apache.commons.lang3.{JavaVersion, SystemUtils}
import org.scalatest.BeforeAndAfterEach

import org.apache.spark.sql.connect.client.util.{IntegrationTestUtils, RemoteSparkSession}

class ReplE2ESuite extends RemoteSparkSession with BeforeAndAfterEach {

  private val executorService = Executors.newSingleThreadExecutor()
  private val TIMEOUT_SECONDS = 30

  private var testSuiteOut: PipedOutputStream = _
  private var ammoniteOut: ByteArrayOutputStream = _
  private var errorStream: ByteArrayOutputStream = _
  private var ammoniteIn: PipedInputStream = _
  private val semaphore: Semaphore = new Semaphore(0)

  private val scalaVersion = Properties.versionNumberString
    .split("\\.")
    .take(2)
    .mkString(".")

  private def getCleanString(out: ByteArrayOutputStream): String = {
    // Remove ANSI colour codes
    // Regex taken from https://stackoverflow.com/a/25189932
    out.toString("UTF-8").replaceAll("\u001B\\[[\\d;]*[^\\d;]", "")
  }

  override def beforeAll(): Unit = {
    // TODO(SPARK-44121) Remove this check condition
    if (SystemUtils.isJavaVersionAtMost(JavaVersion.JAVA_17)) {
      super.beforeAll()
      ammoniteOut = new ByteArrayOutputStream()
      testSuiteOut = new PipedOutputStream()
      // Connect the `testSuiteOut` and `ammoniteIn` pipes
      ammoniteIn = new PipedInputStream(testSuiteOut)
      errorStream = new ByteArrayOutputStream()

      val args = Array("--port", serverPort.toString)
      val task = new Runnable {
        override def run(): Unit = {
          ConnectRepl.doMain(
            args = args,
            semaphore = Some(semaphore),
            inputStream = ammoniteIn,
            outputStream = ammoniteOut,
            errorStream = errorStream)
        }
      }

      executorService.submit(task)
    }
  }

  override def afterAll(): Unit = {
    executorService.shutdownNow()
    super.afterAll()
  }

  override def afterEach(): Unit = {
    semaphore.drainPermits()
  }

  def runCommandsInShell(input: String): String = {
    require(input.nonEmpty)
    // Pad the input with a semaphore release so that we know when the execution of the provided
    // input is complete.
    val paddedInput = input + '\n' + "semaphore.release()\n"
    testSuiteOut.write(paddedInput.getBytes)
    testSuiteOut.flush()
    if (!semaphore.tryAcquire(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      val failOut = getCleanString(ammoniteOut)
      val errOut = getCleanString(errorStream)
      val errorString =
        s"""
          |REPL Timed out while running command: $input
          |Console output: $failOut
          |Error output: $errOut
          |""".stripMargin
      throw new RuntimeException(errorString)
    }
    getCleanString(ammoniteOut)
  }

  def assertContains(message: String, output: String): Unit = {
    val isContain = output.contains(message)
    assert(
      isContain,
      "Ammonite output did not contain '" + message + "':\n" + output +
        s"\nError Output: ${getCleanString(errorStream)}")
  }

  test("Simple query") {
    // Run simple query to test REPL
    val input = """
        |spark.sql("select 1").collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[org.apache.spark.sql.Row] = Array([1])", output)
  }

  test("UDF containing 'def'") {
    val input = """
        |class A(x: Int) { def get = x * 5 + 19 }
        |def dummyUdf(x: Int): Int = new A(x).get
        |val myUdf = udf(dummyUdf _)
        |spark.range(5).select(myUdf(col("id"))).as[Int].collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[Int] = Array(19, 24, 29, 34, 39)", output)
  }

  // SPARK-43198: Switching REPL to CodeClass generation mode causes UDFs defined through lambda
  // expressions to hit deserialization issues.
  // TODO(SPARK-43227): Enable test after fixing deserialization issue.
  ignore("UDF containing lambda expression") {
    val input = """
        |class A(x: Int) { def get = x * 20 + 5 }
        |val dummyUdf = (x: Int) => new A(x).get
        |val myUdf = udf(dummyUdf)
        |spark.range(5).select(myUdf(col("id"))).as[Int].collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[Int] = Array(5, 25, 45, 65, 85)", output)
  }

  test("UDF containing in-place lambda") {
    val input = """
        |class A(x: Int) { def get = x * 42 + 5 }
        |val myUdf = udf((x: Int) => new A(x).get)
        |spark.range(5).select(myUdf(col("id"))).as[Int].collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[Int] = Array(5, 47, 89, 131, 173)", output)
  }

  test("Updating UDF properties") {
    val input = """
        |class A(x: Int) { def get = x * 7 }
        |val myUdf = udf((x: Int) => new A(x).get)
        |val modifiedUdf = myUdf.withName("myUdf").asNondeterministic()
        |spark.range(5).select(modifiedUdf(col("id"))).as[Int].collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[Int] = Array(0, 7, 14, 21, 28)", output)
  }

  test("SPARK-43198: Filter does not throw ammonite-related class initialization exception") {
    val input = """
        |spark.range(10).filter(n => n % 2 == 0).collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[java.lang.Long] = Array(0L, 2L, 4L, 6L, 8L)", output)
  }

  test("Client-side JAR") {
    // scalastyle:off classforname line.size.limit
    val sparkHome = IntegrationTestUtils.sparkHome
    val testJar = Paths
      .get(
        s"$sparkHome/connector/connect/client/jvm/src/test/resources/TestHelloV2_$scalaVersion.jar")
      .toFile

    assert(testJar.exists(), "Missing TestHelloV2 jar!")
    val input = s"""
        |import java.nio.file.Paths
        |def classLoadingTest(x: Int): Int = {
        |  val classloader =
        |    Option(Thread.currentThread().getContextClassLoader).getOrElse(getClass.getClassLoader)
        |  val cls = Class.forName("com.example.Hello$$", true, classloader)
        |  val module = cls.getField("MODULE$$").get(null)
        |  cls.getMethod("test").invoke(module).asInstanceOf[Int]
        |}
        |val classLoaderUdf = udf(classLoadingTest _)
        |
        |val jarPath = Paths.get("${testJar.toString}").toUri
        |spark.addArtifact(jarPath)
        |
        |spark.range(5).select(classLoaderUdf(col("id"))).as[Int].collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[Int] = Array(2, 2, 2, 2, 2)", output)
    // scalastyle:on classforname line.size.limit
  }

  test("Java UDF") {
    val input =
      """
        |import org.apache.spark.sql.api.java._
        |import org.apache.spark.sql.types.LongType
        |
        |val javaUdf = udf(new UDF1[Long, Long] {
        |  override def call(num: Long): Long = num * num + 25L
        |}, LongType).asNondeterministic()
        |spark.range(5).select(javaUdf(col("id"))).as[Long].collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[Long] = Array(25L, 26L, 29L, 34L, 41L)", output)
  }

  test("Java UDF Registration") {
    val input =
      """
        |import org.apache.spark.sql.api.java._
        |import org.apache.spark.sql.types.LongType
        |
        |spark.udf.register("javaUdf", new UDF1[Long, Long] {
        |  override def call(num: Long): Long = num * num * num + 250L
        |}, LongType)
        |spark.sql("select javaUdf(id) from range(5)").as[Long].collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[Long] = Array(250L, 251L, 258L, 277L, 314L)", output)
  }

  test("UDF Registration") {
    // TODO SPARK-44449 make this long again when upcasting is in.
    val input = """
        |class A(x: Int) { def get: Long = x * 100 }
        |val myUdf = udf((x: Int) => new A(x).get)
        |spark.udf.register("dummyUdf", myUdf)
        |spark.sql("select dummyUdf(id) from range(5)").as[Long].collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[Long] = Array(0L, 100L, 200L, 300L, 400L)", output)
  }

  test("UDF closure registration") {
    // TODO SPARK-44449 make this int again when upcasting is in.
    val input = """
        |class A(x: Int) { def get: Long = x * 15 }
        |spark.udf.register("directUdf", (x: Int) => new A(x).get)
        |spark.sql("select directUdf(id) from range(5)").as[Long].collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[Long] = Array(0L, 15L, 30L, 45L, 60L)", output)
  }

  test("call_udf") {
    val input = """
        |val df = Seq(("id1", 1), ("id2", 4), ("id3", 5)).toDF("id", "value")
        |spark.udf.register("simpleUDF", (v: Int) => v * v)
        |df.select($"id", call_udf("simpleUDF", $"value")).collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[org.apache.spark.sql.Row] = Array([id1,1], [id2,16], [id3,25])", output)
  }

  test("call_function") {
    val input = """
        |val df = Seq(("id1", 1), ("id2", 4), ("id3", 5)).toDF("id", "value")
        |spark.udf.register("simpleUDF", (v: Int) => v * v)
        |df.select($"id", call_function("simpleUDF", $"value")).collect()
      """.stripMargin
    val output = runCommandsInShell(input)
    assertContains("Array[org.apache.spark.sql.Row] = Array([id1,1], [id2,16], [id3,25])", output)
  }
}
