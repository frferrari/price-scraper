
import akka.http.scaladsl.model.Uri

val u = Uri("http://www.delcampe.fr/fr/collection?sort=1&page=2")

u.rawQueryString
u.scheme
u.fragment
u.authority
u.isAbsolute
u.query()
u.queryString()
u.toString()
u.rawQueryString
u.withQuery(Uri.Query(("list", "1")))
u.path

