(ns zeromq.build
  (:require [monkey.ci.plugin.clj :as clj]
            [monkey.ci.build
             [core :as bc]
             [shell :as s]]))

#_(clj/deps-library {:test-alias :jeromq:test:junit})

(defn clj-test [ctx]
  (-> (clj/clj-deps ctx {} "-X:jeromq:test:junit")
      (assoc :script ["clojure -Sdeps '{:mvn/local-repo \".m2\"}' -X:jeromq:test:junit"])))

(bc/defpipeline run-tests
  [clj-test])
