(ns zeromq.build
  (:require [monkey.ci.plugin.clj :as clj]
            [monkey.ci.build
             [core :as bc]
             [shell :as s]]))

#_(clj/deps-library {:test-alias :jeromq:test:junit})

(defn clj-test [ctx]
  (-> (clj/clj-deps ctx {} "-X:jeromq:test:junit")
      (update :script concat ["ls -l", (format "echo 'm2 dir: %s'" (s/in-work ctx ".m2"))])))

(bd/defpipeline run-tests
  [clj-test])
