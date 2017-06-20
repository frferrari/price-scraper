# PriceScraper

A module that allows to scrap auction prices from different websites.

A list of urls is available in a DB and from this list the scraper reads the HTML, then it looks
for the last page number to scrap (L), scraps data from page 1 to page L and stops the process as soon
as one auction from a page is already recorded in a MongoDB.

Akka stream is used for this process, using custom graph stages.
