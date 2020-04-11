(ns uxbox.tests.test-services-icons
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

(t/deftest icon-libraries-crud
  (let [id      (uuid/next)
        prof @(th/create-profile db/pool 2)
        team (:default-team prof)]

    (t/testing "create library"
      (let [data {::sm/type :create-icon-library
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

    (t/testing "rename library"
      (let [data {::sm/type :rename-icon-library
                  :name "renamed"
                  :profile-id (:id prof)
                  :team-id (:id team)
                  :id id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query libraries"
      (let [data {::sq/type :icon-libraries
                  :profile-id (:id prof)
                  :team-id (:id team)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= id (get-in result [0 :id])))
          (t/is (= "renamed" (get-in result [0 :name]))))))

    (t/testing "delete library"
      (let [data {::sm/type :delete-icon-library
                  :profile-id (:id prof)
                  :id id}

            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query libraries after delete"
      (let [data {::sq/type :icon-libraries
                  :profile-id (:id prof)
                  :team-id (:id team)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))

(t/deftest icons-crud
  (let [prof @(th/create-profile db/pool 1)
        team (:default-team prof)
        coll @(th/create-icon-library db/pool (:id team) 1)
        icon-id (uuid/next)]

    (t/testing "upload icon to library"
      (let [data {::sm/type :create-icon
                  :id icon-id
                  :profile-id (:id prof)
                  :library-id (:id coll)
                  :name "testfile"
                  :content "<rect></rect>"
                  :metadata {:width 100
                             :height 100
                             :view-box [0 0 100 100]
                             :mimetype "text/svg"}}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (= (:id data) (:id result)))
          (t/is (= (:name data) (:name result)))
          (t/is (= (:content data) (:content result))))))

    (t/testing "list icons by library"
      (let [data {::sq/type :icons
                  :profile-id (:id prof)
                  :library-id (:id coll)}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= icon-id (get-in out [:result 0 :id])))
        (t/is (= "testfile" (get-in out [:result 0 :name])))))

    (t/testing "single icon"
      (let [data {::sq/type :icon
                  :profile-id (:id prof)
                  :id icon-id}
            out (th/try-on! (sq/handle data))]
        ;; (th/print-result! out)

        (t/is (= icon-id (get-in out [:result :id])))
        (t/is (= "testfile" (get-in out [:result :name])))))

    (t/testing "delete icons"
      (let [data {::sm/type :delete-icon
                  :profile-id (:id prof)
                  :id icon-id}
            out (th/try-on! (sm/handle data))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "query icon after delete"
      (let [data {::sq/type :icon
                  :profile-id (:id prof)
                  :id icon-id}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (let [error (:error out)]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :service-error)))

        (let [error (ex-cause (:error out))]
          (t/is (th/ex-info? error))
          (t/is (th/ex-of-type? error :not-found)))))

    (t/testing "query icons after delete"
      (let [data {::sq/type :icons
                  :profile-id (:id prof)
                  :library-id (:id coll)}
            out (th/try-on! (sq/handle data))]

        ;; (th/print-result! out)
        (let [result (:result out)]
          (t/is (= 0 (count result))))))
    ))
