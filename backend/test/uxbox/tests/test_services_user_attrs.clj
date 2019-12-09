(ns uxbox.tests.test-services-user-attrs
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.http :as http]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest test-user-attrs
  (let [{:keys [id] :as user} @(th/create-user db/pool 1)]
    (let [out (th/try-on! (sq/handle {::sq/type :user-attr
                                      :key "foobar"
                                      :user id}))]
      (t/is (nil? (:result out)))

      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :service-error)))

      (let [error (ex-cause (:error out))]
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :not-found))))

    (let [out (th/try-on! (sm/handle {::sm/type :upsert-user-attr
                                      :user id
                                      :key "foobar"
                                      :val {:some #{:value}}}))]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [out (th/try-on! (sq/handle {::sq/type :user-attr
                                      :key "foobar"
                                      :user id}))]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (= {:some #{:value}} (get-in out [:result :val])))
      (t/is (= "foobar" (get-in out [:result :key]))))

    (let [out (th/try-on! (sm/handle {::sm/type :delete-user-attr
                                      :user id
                                      :key "foobar"}))]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [out (th/try-on! (sq/handle {::sq/type :user-attr
                                      :key "foobar"
                                      :user id}))]
      ;; (th/print-result! out)
      (t/is (nil? (:result out)))
      (let [error (:error out)]
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :service-error)))

      (let [error (ex-cause (:error out))]
        (t/is (th/ex-info? error))
        (t/is (th/ex-of-type? error :not-found))))))

