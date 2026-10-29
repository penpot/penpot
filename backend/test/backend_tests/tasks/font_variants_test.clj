;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks.font-variants-test
  (:require
   [app.common.uuid :as uuid]
   [app.jobs :as jobs]
   [app.media.jobs :as mj]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))

(defn- storage
  []
  (sto/resolve th/*system*))

(defn- create-variant-row!
  [profile extra]
  (let [id (uuid/next)]
    (th/db-insert! :team-font-variant
                   (merge {:id          id
                           :team-id     (:default-team-id profile)
                           :profile-id  (:id profile)
                           :font-id     (uuid/next)
                           :font-family "somefont"
                           :font-weight 400
                           :font-style  "normal"}
                          extra))
    id))

(defn- make-job!
  "Submit and claim a `:font-variants` job with the TTF source already
  materialized to `tempfile`."
  [variant-id]
  (let [path   (th/tempfile "backend_tests/test_files/font-1.ttf")
        ref    (mj/materialize-input th/*system* {:path path :mtype "font/ttf"})
        params {:input        ref
                :variant-id   variant-id
                :source-mtype "font/ttf"
                :targets      ["font/otf" "font/woff"]}
        job-id (jobs/submit th/*system*
                            {::jobs/name   :font-variants
                             ::jobs/params params
                             ::jobs/label  (mj/font-label variant-id)})]
    (jobs/claim th/*system* job-id (:scheduled-at (jobs/get-job th/*system* job-id)))
    {:job-id job-id
     :params params}))

(defn- variant-row
  [id]
  (th/db-get :team-font-variant {:id id}))

(defn- capture-objects!
  "A `sto/put-object!` wrapper that records every object it creates."
  []
  (let [original sto/put-object!
        created  (atom [])]
    {:created created
     :redef   (fn [storage params]
                (let [object (original storage params)]
                  (swap! created conj object)
                  object))}))

(defn- created-of
  [captured mtype]
  (->> @(:created captured)
       (filter #(= mtype (-> % meta :content-type)))
       first))

(t/deftest font-variants-job-fills-the-null-columns
  (let [profile    (th/create-profile* 1 {:is-active true})
        variant-id (create-variant-row! profile {})
        {:keys [job-id params]} (make-job! variant-id)
        result     (th/run-task! :font-variants params)
        n          (jobs/complete th/*system* :job-id job-id :result result)
        row        (variant-row variant-id)]

    (t/is (= 1 n))
    (t/is (= 2 (count (:variants result))))

    (t/testing "the requested columns are filled"
      (t/is (uuid? (:otf-file-id row)))
      (t/is (uuid? (:woff1-file-id row))))

    (t/testing "the source column and the one nobody asked for stay empty"
      (t/is (nil? (:ttf-file-id row)))
      (t/is (nil? (:woff2-file-id row))))

    (t/testing "the temporary results and the input are released"
      (doseq [{:keys [id]} (:variants result)]
        (t/is (nil? (sto/get-object (storage) id))))
      (t/is (nil? (sto/get-object (storage) (get-in params [:input :id])))))

    (t/is (= "completed" (:status (jobs/get-job th/*system* job-id))))))

(t/deftest font-variants-job-keeps-a-column-that-already-has-a-file
  (let [profile    (th/create-profile* 1 {:is-active true})
        existing   (sto/put-object! (storage)
                                    {::sto/content (sto/content "existing-otf")
                                     :content-type "font/otf"
                                     :bucket       "team-font-variant"})
        variant-id (create-variant-row! profile {:otf-file-id (:id existing)})
        {:keys [job-id params]} (make-job! variant-id)
        result     (th/run-task! :font-variants params)
        captured   (capture-objects!)]

    (t/is (= 1 (with-redefs [sto/put-object! (:redef captured)]
                 (jobs/complete th/*system* :job-id job-id :result result))))

    (t/testing "the column keeps the file it had"
      (t/is (= (:id existing) (:otf-file-id (variant-row variant-id)))))

    (t/testing "the redundant artifact is deleted and the new one is kept"
      (t/is (nil? (sto/get-object (storage)
                                  (:id (created-of captured "font/otf")))))
      (t/is (some? (sto/get-object (storage)
                                   (:id (created-of captured "font/woff"))))))

    (t/is (uuid? (:woff1-file-id (variant-row variant-id))))))

(t/deftest font-variants-job-writes-nothing-when-the-row-is-gone
  (let [profile    (th/create-profile* 1 {:is-active true})
        variant-id (create-variant-row! profile {})
        {:keys [job-id params]} (make-job! variant-id)
        result     (th/run-task! :font-variants params)
        _          (th/db-force-delete :team-font-variant {:id variant-id})
        captured   (capture-objects!)]

    (t/is (= 1 (with-redefs [sto/put-object! (:redef captured)]
                 (jobs/complete th/*system* :job-id job-id :result result))))

    (t/testing "every artifact created by the hook is deleted"
      (t/is (= 2 (count @(:created captured))))
      (doseq [object @(:created captured)]
        (t/is (nil? (sto/get-object (storage) (:id object))))))

    (t/testing "the input is released anyway"
      (t/is (nil? (sto/get-object (storage) (get-in params [:input :id])))))

    (t/is (= "completed" (:status (jobs/get-job th/*system* job-id))))))

(t/deftest font-variants-job-fails-when-a-result-is-expired
  (let [profile    (th/create-profile* 1 {:is-active true})
        variant-id (create-variant-row! profile {})
        {:keys [job-id params]} (make-job! variant-id)
        expired    {:variants [{:id    (uuid/next)
                                :uri   "https://example.com/assets/by-id/x"
                                :mtype "font/otf"
                                :size  10}]}
        n          (jobs/complete th/*system* :job-id job-id :result expired)
        job        (jobs/get-job th/*system* job-id)
        row        (variant-row variant-id)]

    (t/is (= 0 n))
    (t/is (= "failed" (:status job)))
    (t/is (= :media-result-expired (:code (jobs/decode-job-error (:error job)))))

    (t/testing "the row is not left half done"
      (t/is (nil? (:otf-file-id row)))
      (t/is (nil? (:woff1-file-id row))))

    (t/is (nil? (sto/get-object (storage) (get-in params [:input :id]))))))

(t/deftest font-variants-job-releases-the-temporaries-when-a-write-fails
  (let [profile    (th/create-profile* 1 {:is-active true})
        variant-id (create-variant-row! profile {})
        {:keys [job-id params]} (make-job! variant-id)
        result     (th/run-task! :font-variants params)
        cause      (with-redefs [sto/put-object! (fn [& _]
                                                   (throw (ex-info "storage down" {})))]
                     (try
                       (jobs/complete th/*system* :job-id job-id :result result)
                       nil
                       (catch Throwable cause cause)))]

    (t/is (some? cause))
    (t/is (= "running" (:status (jobs/get-job th/*system* job-id))))

    (t/testing "the temporaries are gone"
      (doseq [{:keys [id]} (:variants result)]
        (t/is (nil? (sto/get-object (storage) id))))
      (t/is (nil? (sto/get-object (storage) (get-in params [:input :id])))))

    (t/testing "the row is untouched and the executor fails the job"
      (t/is (nil? (:otf-file-id (variant-row variant-id))))
      (jobs/fail th/*system* job-id {:type :internal
                                     :code :processing-error
                                     :hint "storage down"})
      (t/is (= "failed" (:status (jobs/get-job th/*system* job-id)))))))
