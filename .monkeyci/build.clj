(ns monkey.zmq.build
  (:require [monkey.ci.plugin.clj :as clj]
            #_[monkey.ci.build.core :as bc]))

(clj/deps-library {:test-alias :jeromq:test:junit})

#_(bc/container-job
 "test"
 {:image "docker.io/clojure:temurin-21-tools-deps-bullseye"
  :script ["clojure -M:jeromq:dev:test -m kaocha.runner --no-capture-output"]})
