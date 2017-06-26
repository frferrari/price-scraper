import com.andycot.pricescraper.models.{PriceScraperUrl, PriceScraperWebsite, PriceScraperWebsiteParameter}
import com.andycot.pricescraper.utils.PriceScraperUrlManager
import org.scalatest.{Matchers, WordSpecLike}

/**
  * Created by Francois FERRARI on 26/06/2017
  */
class PriceScraperUrlManagerSpec
  extends WordSpecLike
    with Matchers {

  val stampBaseUrl = "http://www.andycot.fr/shop/index/timbre-poste-collection"
  val postcardBaseUrl = "http://www.andycot.fr/shop/index/carte-postale"
  val urlWithSortParameter = s"$stampBaseUrl?sort=1"

  "PriceScraperUrlManagerSpec" should {
    "append ?page=1 to an url without any parameter" in {
      PriceScraperUrlManager.addUrlParameters(stampBaseUrl, List("page=1" -> None)) shouldBe s"$stampBaseUrl?page=1"
    }

    "append &page=1 to an url with at least one parameter" in {
      PriceScraperUrlManager.addUrlParameters(urlWithSortParameter, List("page=1" -> None)) shouldBe s"$urlWithSortParameter&page=1"
    }

    "append ?page=1 to an url without any parameter (regex)" in {
      PriceScraperUrlManager.addUrlParameters(stampBaseUrl, List("page=1" -> Some("page=[0-9]+".r))) shouldBe s"$stampBaseUrl?page=1"
    }

    "replace ?page=1 by ?page=4 in an url (regex)" in {
      PriceScraperUrlManager.addUrlParameters(s"$stampBaseUrl?page=1", List("page=4" -> Some("page=[0-9]+".r))) shouldBe s"$stampBaseUrl?page=4"
    }

    "replace &page=1 by &page=4 in an url (regex)" in {
      PriceScraperUrlManager.addUrlParameters(s"$urlWithSortParameter&page=1", List("page=4" -> Some("page=[0-9]+".r))) shouldBe s"$urlWithSortParameter&page=4"
    }

    "append &page=4 to an url with at least one parameter (regex)" in {
      PriceScraperUrlManager.addUrlParameters(s"$urlWithSortParameter", List("page=4" -> Some("page=[0-9]+".r))) shouldBe s"$urlWithSortParameter&page=4"
    }

    "generate 4 urls given a" in {
      val priceScraperBaseUrl = PriceScraperUrl("andycot", stampBaseUrl)
      val priceScraperWebsites = List(
        PriceScraperWebsite("andycot", stampBaseUrl, false, List(PriceScraperWebsiteParameter("sort=1", None), PriceScraperWebsiteParameter("size=120", None)))
      )

      PriceScraperUrlManager.generateAllUrls(priceScraperBaseUrl, priceScraperWebsites, 4) shouldBe List(
        "http://www.andycot.fr/shop/index/timbre-poste-collection?sort=1&size=120&page=1",
        "http://www.andycot.fr/shop/index/timbre-poste-collection?sort=1&size=120&page=2",
        "http://www.andycot.fr/shop/index/timbre-poste-collection?sort=1&size=120&page=3",
        "http://www.andycot.fr/shop/index/timbre-poste-collection?sort=1&size=120&page=4"
      )
    }
  }
}
