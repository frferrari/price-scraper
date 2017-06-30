import com.andycot.pricescraper.business.PriceScraperDCP
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

  "Extracting the offer count" should {
    "return Some(1) for a single offer" in {
      PriceScraperDCP.extractOfferCount("Offre (1)") shouldBe Some(1)
    }

    "return Some(5) for 5 offers" in {
      PriceScraperDCP.extractOfferCount("Offre (5)") shouldBe Some(5)
    }

    "return Some(14) for 14 offers" in {
      PriceScraperDCP.extractOfferCount("Offre (14)") shouldBe Some(14)
    }

    "return None for a wrong offer text" in {
      PriceScraperDCP.extractOfferCount("Offre (") shouldBe None
    }
  }
}
