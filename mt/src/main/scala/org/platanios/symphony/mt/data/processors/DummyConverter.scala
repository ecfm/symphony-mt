/* Copyright 2017-18, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.symphony.mt.data.processors

import org.platanios.symphony.mt.Language
import org.platanios.symphony.mt.data._

import better.files.File
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * @author Emmanouil Antonios Platanios
  */
object DummyConverter extends FileProcessor {
  private val logger = Logger(LoggerFactory.getLogger("Data / Dummy Converter"))

  private val MISSING_SENTENCE: String = "__NULL__"

  override def processPair(file1: File, file2: File, language1: Language, language2: Language): (File, File) = {
    assert(file1 == file2, "The DummyConverter converter assumes that the data for all languages is stored in the same file.")
    val textFileNamePrefix = s"${file1.nameWithoutExtension}.${language1.abbreviation}-${language2.abbreviation}"
    val textFile1 = file1.sibling(s"$textFileNamePrefix.${language1.abbreviation}.txt")
    val textFile2 = file2.sibling(s"$textFileNamePrefix.${language2.abbreviation}.txt")
    (textFile1, textFile2)
  }
}
