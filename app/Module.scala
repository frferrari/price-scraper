/**
  * Created by Francois FERRARI on 20/06/2017
  */
import com.andycot.pricescraper.services.{PriceScraper, PriceScraperImpl}
import com.google.inject.AbstractModule

class Module extends AbstractModule {
  def configure() = {
    bind(classOf[PriceScraper])
      .to(classOf[PriceScraperImpl]).asEagerSingleton()
  }
}