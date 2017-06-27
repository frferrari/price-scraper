import akka.http.scaladsl.model.Uri
import com.andycot.pricescraper.models.{PriceScraperUrl, PriceScraperWebsite, PriceScraperQueryParameter}
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
    "generate 4 urls given a" in {
      val priceScraperBaseUrl = PriceScraperUrl("andycot", stampBaseUrl)
      val priceScraperWebsites = List(
        PriceScraperWebsite(
          "andycot",
          stampBaseUrl,
          canSortByAuctionEndDate = false,
          List(
            PriceScraperQueryParameter("sort", "1"), PriceScraperQueryParameter("size", "120")
          )
        )
      )

      PriceScraperUrlManager.generateAllUrls(priceScraperBaseUrl, priceScraperWebsites, 4) shouldBe List(
        PriceScraperUrl("andycot", Uri("http://www.andycot.fr/shop/index/timbre-poste-collection?sort=1&size=120&page=1")),
        PriceScraperUrl("andycot", Uri("http://www.andycot.fr/shop/index/timbre-poste-collection?sort=1&size=120&page=2")),
        PriceScraperUrl("andycot", Uri("http://www.andycot.fr/shop/index/timbre-poste-collection?sort=1&size=120&page=3")),
        PriceScraperUrl("andycot", Uri("http://www.andycot.fr/shop/index/timbre-poste-collection?sort=1&size=120&page=4"))
      )
    }
  }
}
