(ns monkey.zmq.req-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.zmq
             [common :refer [close-all]]
             [req :as sut]]
            [zeromq.zmq :as z]))

(defn- test-addr []
  (str "inproc://" (random-uuid)))

(deftest request-reply
  (testing "server receives request and sends reply"
    (let [handler (fn [{:keys [message]}]
                    {:received message})
          ctx (z/context 1)
          addr (test-addr)
          s (sut/server ctx addr handler)
          c (sut/client ctx addr)]
      (is (some? s))
      (is (ifn? c))
      (let [r (c {:message "test request"})]
        (is (= "test request" (:received r))))
      (is (close-all [c s ctx]))))

  (testing "throws when no server"
    (let [ctx (z/context 1)
          c (-> (sut/client ctx (test-addr))
                (assoc :request-timeout 1000))]
      (is (thrown? Exception (c {:message "Test message"})))
      (is (close-all [c ctx])))))
