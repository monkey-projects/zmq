(ns monkey.zmq.req-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.zmq.req :as sut]
            [zeromq.zmq :as z]))

(deftest request-reply
  (testing "server receives request and sends reply"
    (let [handler (fn [{:keys [message]}]
                    {:received message})
          ctx (z/context 1)
          addr (str "inproc://" (random-uuid))
          s (sut/server ctx addr handler)
          c (sut/client ctx addr)]
      (is (some? s))
      (is (ifn? c))
      (let [r (c {:message "test request"})]
        (is (= "test request" (:received r))))
      (is (every? (comp nil? (memfn close)) [c s ctx])))))
