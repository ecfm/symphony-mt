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

package org.platanios.symphony.mt.data.loaders

import org.platanios.symphony.mt.Language
import org.platanios.symphony.mt.Language._
import org.platanios.symphony.mt.data._
import org.platanios.symphony.mt.data.processors.{FileProcessor, DummyConverter}

import better.files._

import java.nio.file.Path

/** This is a loader for the TED talks dataset in [[https://github.com/neulab/word-embeddings-for-nmt]].
  *
  * @author Emmanouil Antonios Platanios
  */
class HW2Loader(
                      override val srcLanguage: Language,
                      override val tgtLanguage: Language,
                      val config: DataConfig
                    ) extends ParallelDatasetLoader(srcLanguage, tgtLanguage) {
  require(
    HW2Loader.isLanguagePairSupported(srcLanguage, tgtLanguage),
    "The provided language pair is not supported by the HW2 dataset.")

  override def name: String = "HW2"

  override def dataConfig: DataConfig = {
    config.copy(workingDir =
      config.workingDir
        .resolve(s"${srcLanguage.abbreviation}-${tgtLanguage.abbreviation}"))
  }

  override def downloadsDir: Path = config.workingDir

  /** Sequence of files to download as part of this dataset. */
  override def filesToDownload: Seq[String] = Seq.empty

  /** Returns all the corpora (tuples containing tag, source file, target file, and a file processor to use)
    * of this dataset type. */
  override def corpora(datasetType: DatasetType): Seq[(ParallelDataset.Tag, File, File, FileProcessor)] = {
    datasetType match {
      case Train => Seq((HW2Loader.Train,
        File(downloadsDir) / HW2Loader.filename / "train",
        File(downloadsDir) / HW2Loader.filename / "train",
        DummyConverter))
      case Dev => Seq((HW2Loader.Dev,
        File(downloadsDir) / HW2Loader.filename / "dev",
        File(downloadsDir) / HW2Loader.filename / "dev",
        DummyConverter))
      case Test => Seq((HW2Loader.Test,
        File(downloadsDir) / HW2Loader.filename / "test",
        File(downloadsDir) / HW2Loader.filename / "test",
        DummyConverter))
    }
  }
}

object HW2Loader {
  val url: String = "http://phontron.com/data"
  val filename: String = "HW2"

  val supportedLanguagePairs: Set[(Language, Language)] = Set(
    Albanian, Arabic, Armenian, Azerbaijani, Basque, Belarusian, Bengali, Bosnian, Bulgarian, Burmese, Chinese,
    ChineseMainland, ChineseTaiwan, Croatian, Czech, Danish, Dutch, English, Esperanto, Estonian, Finnish, French,
    FrenchCanada, Galician, Georgian, German, Greek, Hebrew, Hindi, Hungarian, Indonesian, Italian, Japanese, Kazakh,
    Korean, Kurdish, Lithuanian, Macedonian, Malay, Marathi, Mongolian, Norwegian, Persian, Polish, Portuguese,
    PortugueseBrazil, Romanian, Russian, Tamil, Thai, Serbian, Slovak, Slovenian, Swedish, Spanish, Turkish, Ukranian,
    Urdu, Vietnamese).toSeq.combinations(2).map(p => (p(0), p(1))).toSet

  def isLanguagePairSupported(srcLanguage: Language, tgtLanguage: Language): Boolean = {
    supportedLanguagePairs.contains((srcLanguage, tgtLanguage)) ||
      supportedLanguagePairs.contains((tgtLanguage, srcLanguage))
  }

  def apply(
             srcLanguage: Language,
             tgtLanguage: Language,
             dataConfig: DataConfig
           ): HW2Loader = {
    new HW2Loader(srcLanguage, tgtLanguage, dataConfig)
  }

  trait Tag extends ParallelDataset.Tag

  object Tag {
    @throws[IllegalArgumentException]
    def fromName(name: String): Tag = name match {
      case "train" => Train
      case "dev" => Dev
      case "test" => Test
      case _ => throw new IllegalArgumentException(s"'$name' is not a valid HW2-Talks tag.")
    }
  }

  case object Train extends Tag {
    override val value: String = "train"
  }

  case object Dev extends Tag {
    override val value: String = "dev"
  }

  case object Test extends Tag {
    override val value: String = "test"
  }
}
