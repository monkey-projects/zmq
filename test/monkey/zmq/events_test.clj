(ns monkey.zmq.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.zmq
             [common :refer [close-all]]
             [events :as sut]]
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

(def opts {:poll-timeout 100})

(deftest event-server
  (testing "passes received events to handler"
    (let [ctx (z/context 1)
          recv (atom [])
          handler (partial swap! recv conj)
          addr (str "inproc://" (random-uuid))
          e (sut/event-server ctx addr handler opts)
          p (sut/event-poster ctx addr)]
      (is (some? e))
      (is (ifn? p))
      (is (true? (p "test event")))
      (is (not= ::timeout (wait-for #(not-empty @recv))))
      (is (= ["test event"] @recv))
      (is (close-all [p e ctx]))))
 
  (testing "can post multiple events from one source"
    (let [ctx (z/context 1)
          recv (atom [])
          handler (partial swap! recv conj)
          addr (str "inproc://" (random-uuid))
          e (sut/event-server ctx addr handler opts)
          p (sut/event-poster ctx addr)
          n 10]
      (is (->> (range n)
               (map (comp p (partial str "Event ")))
               (every? true?)))
      (is (not= ::timeout (wait-for #(= n (count @recv)))))
      (is (= "Event 0" (first @recv)))
      (is (close-all [p e ctx]))))

  (testing "can post events from multiple sources"
    (let [ctx (z/context 1)
          recv (atom [])
          handler (partial swap! recv conj)
          addr (str "inproc://" (random-uuid))
          e (sut/event-server ctx addr handler opts)
          n 10
          p (doall (repeatedly n #(sut/event-poster ctx addr)))]
      (is (->> p
               (map-indexed (fn [i poster]
                              (poster (str "Event " i))))
               (every? true?)))
      (is (not= ::timeout (wait-for #(= n (count @recv)))))
      (is (close-all (concat p [e ctx]))))))
