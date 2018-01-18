(defproject caribou/antlers "0.6.1"
  :description "Swift, robust templating in Clojure"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [scout "0.1.0"]
                 [quoin "0.1.1"]
                 [slingshot "0.8.0"]
                 [org.clojure/core.cache "0.6.1"]]
  :aliases {"all" ["with-profile" "dev:dev,clj1.4:dev,clj1.5"]}
  :repositories {"sonatype" {:url "http://oss.sonatype.org/content/repositories/releases"
                             :snapshots false
                             :releases {:checksum :fail :update :always}}
                 "sonatype-snapshots" {:url "http://oss.sonatype.org/content/repositories/snapshots"
                                       :snapshots true
                                       :releases {:checksum :fail :update :always}}})

