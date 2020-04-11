(ns uxbox.tests.test-services-colors
  (:require
   [clojure.test :as t]
   [promesa.core :as p]
   [datoteka.core :as fs]
   [clojure.java.io :as io]
   [uxbox.db :as db]
   [uxbox.core :refer [system]]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.util.storage :as ust]
   [uxbox.common.uuid :as uuid]
   [uxbox.tests.helpers :as th]
   [vertx.core :as vc]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest color-libraries-crud
  (let [id    (uuid/next)
        prof @(th/create-profile db/pool 2)
        team (:default-team prof)]

    (t/testing "create library"
      (let [data {::sm/type :create-color-library
                  :name "sample library"
                  :profile-id (:id prof)
                  :team-id (:id team)
                  :id id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= id (:id result)))
          (t/is (= (:id team)  (:team-id result)))
          (t/is (= (:name data) (:name result))))))

    (t/testing "update library"
      (let [data {::sm/type :rename-color-library
                  :name "renamed"
                  :profile-id (:id prof)
                  :id id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query libraries"
      (let [data {::sq/type :color-libraries
                  :profile-id (:id prof)
                  :team-id (:id team)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= (:id team) (get-in result [0 :team-id])))
          (t/is (= "renamed" (get-in result [0 :name]))))))

    (t/testing "delete library"
      (let [data {::sm/type :delete-color-library
                  :profile-id (:id prof)
                  :id id}

            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query libraries after delete"
      (let [data {::sq/type :color-libraries
                  :profile-id (:id prof)
                  :team-id (:id team)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))

(t/deftest colors-crud
  (let [prof @(th/create-profile db/pool 1)
        team (:default-team prof)
        coll @(th/create-color-library db/pool (:id team) 1)
        color-id (uuid/next)]

    (t/testing "upload color to library"
      (let [data {::sm/type :create-color
                  :id color-id
                  :profile-id (:id prof)
                  :library-id (:id coll)
                  :name "testfile"
                  :content "#222222"}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:id data) (:id result)))
          (t/is (= (:name data) (:name result)))
          (t/is (= (:content data) (:content result))))))

    (t/testing "list colors by library"
      (let [data {::sq/type :colors
                  :profile-id (:id prof)
                  :library-id (:id coll)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= color-id (get-in out [:result 0 :id])))
        (t/is (= "testfile" (get-in out [:result 0 :name])))))

    (t/testing "single color"
      (let [data {::sq/type :color
                  :profile-id (:id prof)
                  :id color-id}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= color-id (get-in out [:result :id])))
        (t/is (= "testfile" (get-in out [:result :name])))))

    (t/testing "delete colors"
      (let [data {::sm/type :delete-color
                  :profile-id (:id prof)
                  :id color-id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (get-in out [:result])))))

    (t/testing "query color after delete"
      (let [data {::sq/type :color
                  :profile-id (:id prof)
                  :id color-id}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :service-error)))

        (let [error (ex-cause (:error out))]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :not-found)))))

    (t/testing "query colors after delete"
      (let [data {::sq/type :colors
                  :profile-id (:id prof)
                  :library-id (:id coll)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))
