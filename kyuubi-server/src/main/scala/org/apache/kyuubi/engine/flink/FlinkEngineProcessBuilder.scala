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

package org.apache.kyuubi.engine.flink

import java.io.{File, FilenameFilter}
import java.net.URI
import java.nio.file.{Files, Path, Paths}

import com.google.common.annotations.VisibleForTesting

import org.apache.kyuubi._
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.ENGINE_FLINK_MAIN_RESOURCE
import org.apache.kyuubi.engine.ProcBuilder
import org.apache.kyuubi.engine.flink.FlinkEngineProcessBuilder.FLINK_ENGINE_BINARY_FILE
import org.apache.kyuubi.operation.log.OperationLog

/**
 * A builder to build flink sql engine progress.
 */
class FlinkEngineProcessBuilder(
    override val proxyUser: String,
    override val conf: KyuubiConf,
    val extraEngineLog: Option[OperationLog] = None)
  extends ProcBuilder with Logging {

  override protected def executable: String = {
    val flinkEngineHomeOpt = env.get("FLINK_ENGINE_HOME").orElse {
      val cwd = getClass.getProtectionDomain.getCodeSource.getLocation.getPath
        .split("kyuubi-server")
      assert(cwd.length > 1)
      Option(
        Paths.get(cwd.head)
          .resolve("externals")
          .resolve("kyuubi-flink-sql-engine")
          .toFile)
        .map(_.getAbsolutePath)
    }

    flinkEngineHomeOpt.map { dir =>
      Paths.get(dir, "bin", FLINK_ENGINE_BINARY_FILE).toAbsolutePath.toFile.getCanonicalPath
    } getOrElse {
      throw KyuubiSQLException("FLINK_ENGINE_HOME is not set! " +
        "For more detail information on installing and configuring Flink, please visit " +
        "https://kyuubi.apache.org/docs/stable/deployment/settings.html#environments")
    }
  }

  override protected def mainResource: Option[String] = {
    val jarName = s"${module}_$SCALA_COMPILE_VERSION-$KYUUBI_VERSION.jar"
    // 1. get the main resource jar for user specified config first
    conf.get(ENGINE_FLINK_MAIN_RESOURCE).filter { userSpecified =>
      // skip check exist if not local file.
      val uri = new URI(userSpecified)
      val schema = if (uri.getScheme != null) uri.getScheme else "file"
      schema match {
        case "file" => Files.exists(Paths.get(userSpecified))
        case _ => true
      }
    }.orElse {
      // 2. get the main resource jar from system build default
      env.get(KyuubiConf.KYUUBI_HOME)
        .map { Paths.get(_, "externals", "engines", "flink", jarName) }
        .filter(Files.exists(_)).map(_.toAbsolutePath.toFile.getCanonicalPath)
    }.orElse {
      // 3. get the main resource from dev environment
      Option(Paths.get("externals", module, "target", jarName))
        .filter(Files.exists(_)).orElse {
          Some(Paths.get("..", "externals", module, "target", jarName))
        }.map(_.toAbsolutePath.toFile.getCanonicalPath)
    }
  }

  override protected def module: String = "kyuubi-flink-sql-engine"

  override protected def mainClass: String = "org.apache.kyuubi.engine.flink.FlinkSQLEngine"

  override protected def childProcEnv: Map[String, String] = conf.getEnvs +
    ("FLINK_HOME" -> FLINK_HOME) +
    ("FLINK_CONF_DIR" -> s"$FLINK_HOME/conf") +
    ("FLINK_SQL_ENGINE_JAR" -> mainResource.get) +
    ("FLINK_SQL_ENGINE_DYNAMIC_ARGS" ->
      conf.getAll.map { case (k, v) => s"-D$k=$v" }.mkString(" "))

  override protected def commands: Array[String] = Array(executable)

  override protected val workingDir: Path = {
    env.get("KYUUBI_WORK_DIR_ROOT").map { root =>
      val workingRoot = Paths.get(root).toAbsolutePath
      if (!Files.exists(workingRoot)) {
        debug(s"Creating KYUUBI_WORK_DIR_ROOT at $workingRoot")
        Files.createDirectories(workingRoot)
      }
      if (Files.isDirectory(workingRoot)) {
        workingRoot.toString
      } else null
    }.map { rootAbs =>
      val working = Paths.get(rootAbs, proxyUser)
      if (!Files.exists(working)) {
        debug(s"Creating $proxyUser's working directory at $working")
        Files.createDirectories(working)
      }
      if (Files.isDirectory(working)) {
        working
      } else {
        Utils.createTempDir(rootAbs, proxyUser)
      }
    }.getOrElse {
      Utils.createTempDir(namePrefix = proxyUser)
    }
  }

  override def toString: String = commands.map {
    case arg if arg.startsWith("--") => s"\\\n\t$arg"
    case arg => arg
  }.mkString(" ")

  @VisibleForTesting
  def FLINK_HOME: String = {
    // prepare FLINK_HOME
    val flinkHomeOpt = env.get("FLINK_HOME").orElse {
      val cwd = getClass.getProtectionDomain.getCodeSource.getLocation.getPath
        .split("kyuubi-server")
      assert(cwd.length > 1)
      Option(
        Paths.get(cwd.head)
          .resolve("externals")
          .resolve("kyuubi-download")
          .resolve("target")
          .toFile
          .listFiles(new FilenameFilter {
            override def accept(dir: File, name: String): Boolean = {
              dir.isDirectory && name.startsWith("flink-")
            }
          }))
        .flatMap(_.headOption)
        .map(_.getAbsolutePath)
    }

    flinkHomeOpt.map { dir =>
      dir
    } getOrElse {
      throw KyuubiSQLException("FLINK_HOME is not set! " +
        "For more detail information on installing and configuring Flink, please visit " +
        "https://kyuubi.apache.org/docs/stable/deployment/settings.html#environments")
    }
  }

  private def useKeytab(): Boolean = false
}

object FlinkEngineProcessBuilder {
  final val APP_KEY = "yarn.application.name"
  final val TAG_KEY = "yarn.tags"

  final private val FLINK_ENGINE_BINARY_FILE = "flink-sql-engine.sh"
}