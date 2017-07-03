import com.andycot.pricescraper.business.PriceScraperDCP
import com.andycot.pricescraper.models.PriceScraperYearRange
import org.scalatest.{Matchers, WordSpecLike}

/**
  * Created by Francois FERRARI on 26/06/2017
  */
class PriceScraperDCPSpec
  extends WordSpecLike
    with Matchers {

  "Generating an Instant from a String" should {
    "succeed when the month doesn't contain any french accent character" in {
      PriceScraperDCP.toInstant("vendredi 30 juin 2017 18:47").getEpochSecond shouldBe 1498848420
    }

    "succeed when the month is août (august)" in {
      PriceScraperDCP.toInstant("mardi 1 août 2017 18:47").getEpochSecond shouldBe 1501613220
    }

    "succeed when the month is février (february)" in {
      PriceScraperDCP.toInstant("mardi 28 février 2017 18:47").getEpochSecond shouldBe 1488307620
    }
  }

  "Extracting the startedAt date, time and visit count part from a string" should {
    "succeed when the month doesn't contain any french accent character" in {
      PriceScraperDCP.extractStartedAtText("Début de la vente : jeudi 22 juin 2017 à 13:55 33 visites") shouldBe ("jeudi 22 juin 2017 13:55", 33)
    }

    "succeed when the month is août (august)" in {
      PriceScraperDCP.extractStartedAtText("Début de la vente : mardi 1 août 2017 à 13:55 1 visite") shouldBe ("mardi 1 août 2017 13:55", 1)
    }

    "succeed when the month is février (february)" in {
      PriceScraperDCP.extractStartedAtText("Début de la vente : mardi 28 février 2017 à 13:55 1 visite") shouldBe ("mardi 28 février 2017 13:55", 1)
    }
  }

  "Extracting the soldAt date and time part from a string" should {
    "succeed when the month doesn't contain any french accent character" in {
      PriceScraperDCP.extractSoldAtText("Vendue le jeudi 22 juin 2017 13:55") shouldBe "jeudi 22 juin 2017 13:55"
    }

    "succeed when the month is août (august)" in {
      PriceScraperDCP.extractSoldAtText("Vendue le mardi 1 août 2017 13:55") shouldBe "mardi 1 août 2017 13:55"
    }

    "succeed when the month is février (february)" in {
      PriceScraperDCP.extractSoldAtText("Vendue le mardi 28 février 2017 13:55") shouldBe "mardi 28 février 2017 13:55"
    }
  }

  "Guessing the year from an auction title" should {
    "succeed to extract a year value in the range of the valid year values given no PriceScraperRange" in {
      PriceScraperDCP.guessYear("2017 MNH", None) shouldBe Some(PriceScraperYearRange(2017, 2017))
    }
    "fail to extract a year value lower than the range of the valid year values given no PriceScraperRange" in {
      PriceScraperDCP.guessYear("1200 MNH", None) shouldBe None
    }
    "fail to extract a year value higher than the range of the valid year values given no PriceScraperRange" in {
      PriceScraperDCP.guessYear("3000 MNH", None) shouldBe None
    }

    //
    //
    //
    "succeed to extract a year value in the range of the valid year values given a PriceScraperRange" in {
      PriceScraperDCP.guessYear("1950 MNH", Some(PriceScraperYearRange(1900, 2000))) shouldBe Some(PriceScraperYearRange(1950, 1950))
    }
    "fail to extract a year value lower than the range of the valid year values given a PriceScraperRange" in {
      PriceScraperDCP.guessYear("1800 MNH", Some(PriceScraperYearRange(1900, 2000))) shouldBe None
    }
    "fail to extract a year value higher than the range of the valid year values given a PriceScraperRange" in {
      PriceScraperDCP.guessYear("2002 MNH", Some(PriceScraperYearRange(1900, 2000))) shouldBe None
    }

    //
    //
    //
    "succeed to extract the year value when there's only one valid year (located at the beginning of the given string)" in {
      PriceScraperDCP.guessValidYear("2017 MNH Alderney sea birds", PriceScraperDCP.validYearCommonCase) shouldBe Some(PriceScraperYearRange(2017, 2017))
    }
    "succeed to extract the year value when there's only one valid year (located in the middle of the given string)" in {
      PriceScraperDCP.guessValidYear("MNH Alderney 2017 sea birds", PriceScraperDCP.validYearCommonCase) shouldBe Some(PriceScraperYearRange(2017, 2017))
    }
    "succeed to extract the year value when there's only one valid year (located at the end of the given string)" in {
      PriceScraperDCP.guessValidYear("MNH Alderney sea birds 2017", PriceScraperDCP.validYearCommonCase) shouldBe Some(PriceScraperYearRange(2017, 2017))
    }

    //
    //
    //
    "succeed to extract the year when there's a valid year value (located at the beginning of the string) and an invalid year" in {
      PriceScraperDCP.guessValidYear("2017 MNH Alderney 1600 sea birds", PriceScraperDCP.validYearCommonCase) shouldBe Some(PriceScraperYearRange(2017, 2017))
    }
    "succeed to extract the year when there's a valid year value (located in the middle of the string) and an invalid year" in {
      PriceScraperDCP.guessValidYear("MNH Alderney 2017 sea 1600 birds", PriceScraperDCP.validYearCommonCase) shouldBe Some(PriceScraperYearRange(2017, 2017))
    }
    "succeed to extract the year when there's a valid year value (located at the end of the string) and an invalid year" in {
      PriceScraperDCP.guessValidYear("MNH Alderney 1600 sea birds 2017", PriceScraperDCP.validYearCommonCase) shouldBe Some(PriceScraperYearRange(2017, 2017))
    }

    //
    //
    //
    "fail to extract the year when there's an invalid year value (located at the beginning of the string)" in {
      PriceScraperDCP.guessValidYear("201 MNH Alderney sea birds", PriceScraperDCP.validYearCommonCase) shouldBe None
    }
    "fail to extract the year when there's an invalid year value (located in the middle of the string)" in {
      PriceScraperDCP.guessValidYear("MNH 201 Alderney sea birds", PriceScraperDCP.validYearCommonCase) shouldBe None
    }
    "fail to extract the year when there's an invalid year value (located at the end of the string)" in {
      PriceScraperDCP.guessValidYear("MNH Alderney sea birds 201", PriceScraperDCP.validYearCommonCase) shouldBe None
    }

    //
    //
    //
    "fail to extract the year when there's a valid year value followed by other digits (located at the beginning of the string)" in {
      PriceScraperDCP.guessValidYear("2017012 MNH Alderney sea birds", PriceScraperDCP.validYearCommonCase) shouldBe None
    }
    "fail to extract the year when there's a valid year value followed by other digits (located in the middle of the string)" in {
      PriceScraperDCP.guessValidYear("MNH Alderney 2017012 sea birds", PriceScraperDCP.validYearCommonCase) shouldBe None
    }
    "fail to extract the year when there's a valid year value followed by other digits (located at the end of the string)" in {
      PriceScraperDCP.guessValidYear("MNH Alderney sea birds 2017012", PriceScraperDCP.validYearCommonCase) shouldBe None
    }

    //
    //
    //
    "fail to extract the year when there's a valid year value followed by another valid year (located at the beginning of the string)" in {
      PriceScraperDCP.guessValidYear("20172018 MNH Alderney sea birds", PriceScraperDCP.validYearCommonCase) shouldBe None
    }
    "fail to extract the year when there's a valid year value followed by another valid year (located in the middle of the string)" in {
      PriceScraperDCP.guessValidYear("MNH Alderney 20172018 sea birds", PriceScraperDCP.validYearCommonCase) shouldBe None
    }
    "fail to extract the year when there's a valid year value followed by another valid year (located at the end of the string)" in {
      PriceScraperDCP.guessValidYear("MNH Alderney sea birds 20172018", PriceScraperDCP.validYearCommonCase) shouldBe None
    }

    //
    //
    //
    "fail to extract the year when there are two valid year values (one located at the beginning of the string) and another valid year" in {
      PriceScraperDCP.guessValidYear("2017 MNH Alderney 2018 sea birds", PriceScraperDCP.validYearCommonCase) shouldBe None
    }
    "fail to extract the year when there are two valid year values (one located in the middle of the string) followed by another valid year" in {
      PriceScraperDCP.guessValidYear("MNH Alderney 2017 sea 2018 birds", PriceScraperDCP.validYearCommonCase) shouldBe None
    }
    "fail to extract the year when there are two valid year values (one located at the end of the string) followed by another valid year" in {
      PriceScraperDCP.guessValidYear("MNH 2018 Alderney sea birds 2017", PriceScraperDCP.validYearCommonCase) shouldBe None
    }
  }
}
