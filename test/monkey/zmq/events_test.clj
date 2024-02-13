(ns monkey.zmq.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.zmq.events :as sut]
            [zeromq.zmq :as z]))

(defn wait-for [p & [timeout]]
  (let [s (System/currentTimeMillis)]
    (loop [n s
           v (p)]
      (if (> n (+ s (or timeout 1000)))
        ::timeout
        (if-not v
          (do
            (Thread/sleep 10)
            (recur (System/currentTimeMillis)
                   (p)))
          v)))))

(deftest event-server
  (testing "passes received events to handler"
    (let [ctx (z/context 1)
          recv (atom [])
          handler (partial swap! recv conj)
          addr (str "inproc://" (random-uuid))
          e (sut/event-server ctx addr handler)
          p (sut/event-poster ctx addr)]
      (is (some? e))
      (is (ifn? p))
      (is (nil? (p "test event")))
      (is (not= ::timeout (wait-for #(not-empty @recv))))
      (is (= ["test event"] @recv))
      (is (every? (comp nil? (memfn close)) [p e ctx])))))
