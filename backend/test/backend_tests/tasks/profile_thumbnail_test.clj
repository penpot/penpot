;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks.profile-thumbnail-test
  (:require
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.jobs :as jobs]
   [app.media.jobs :as mj]
   [app.media.local :as media.local]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [datoteka.io :as io]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))

(defn- storage
  []
  (sto/resolve th/*system*))

(defn- make-job!
  "Submit and claim a `:profile-thumbnail` job owned by the profile, with the
  photo already materialized to `tempfile`."
  [profile-id fixture mtype]
  (let [path   (th/tempfile fixture)
        ref    (mj/materialize-input th/*system* {:path path :mtype mtype})
        params {:input ref}
        job-id (jobs/submit th/*system*
                            {::jobs/name       :profile-thumbnail
                             ::jobs/params     params
                             ::jobs/profile-id profile-id
                             ::jobs/label      (mj/profile-label profile-id)})]
    (jobs/claim th/*system* job-id (:scheduled-at (jobs/get-job th/*system* job-id)))
    {:job-id job-id
     :params params}))

(defn- make-profile
  []
  (th/create-profile* 1 {:is-active true}))

(defn- profile-row
  [id]
  (th/db-get :profile {:id id}))

(defn- add-old-photo!
  [profile]
  (let [object (sto/put-object! (storage)
                                {::sto/content      (sto/content "old-photo")
                                 :content-type      "image/jpeg"
                                 :bucket            "profile"})]
    (th/db-update! :profile {:photo-id (:id object)} {:id (:id profile)})
    object))

(defn- image-size
  [object]
  (let [path (tmp/tempfile :prefix "profile-check-" :suffix ".jpg")]
    (try
      (io/write* path (sto/get-object-bytes (storage) object))
      (let [info (media.local/process th/*system*
                                      {:cmd   :info
                                       :input {:path  path
                                               :mtype "image/jpeg"}})]
        [(:width info) (:height info)])
      (finally
        (fs/delete path)))))

(t/deftest profile-thumbnail-job-updates-the-profile-and-touches-the-old-photo
  (let [profile (make-profile)
        old     (add-old-photo! profile)
        {:keys [job-id params]} (make-job! (:id profile)
                                           "backend_tests/test_files/sample.png"
                                           "image/png")
        result  (th/run-task! :profile-thumbnail params)
        n       (jobs/complete th/*system* :job-id job-id :result result)
        row     (profile-row (:id profile))
        object  (sto/get-object (storage) (:photo-id row))]

    (t/is (= 1 n))
    (t/is (uuid? (:photo-id row)))
    (t/is (not= (:id old) (:photo-id row)))
    (t/is (= "profile" (-> object meta :bucket)))
    (t/is (= "image/jpeg" (-> object meta :content-type)))
    (t/is (= [256 256] (image-size object)))

    (t/testing "the old photo is touched, not deleted"
      (t/is (some? (:touched-at (th/db-get :storage-object {:id (:id old)}))))
      (t/is (some? (sto/get-object (storage) (:id old)))))

    (t/testing "the temporary result and the input are released"
      (t/is (nil? (sto/get-object (storage) (get-in result [:photo :id]))))
      (t/is (nil? (sto/get-object (storage) (get-in params [:input :id])))))

    (t/is (= "completed" (:status (jobs/get-job th/*system* job-id))))))

(t/deftest profile-thumbnail-job-rejects-a-disallowed-format
  (let [validator (sm/validator mj/schema:profile-thumbnail)]
    (t/is (false? (validator {:input {:id    (uuid/next)
                                      :mtype "image/gif"
                                      :size  10}}))
          "the job boundary rejects it before the handler")
    (t/is (true? (validator {:input {:id    (uuid/next)
                                     :mtype "image/webp"
                                     :size  10}}))))

  (let [profile (make-profile)]
    (t/is (thrown? Exception
                   (make-job! (:id profile)
                              "backend_tests/test_files/sample.png"
                              "image/gif")))))

(t/deftest profile-thumbnail-job-releases-the-input-on-failure-and-cancel
  (let [profile (make-profile)
        old     (add-old-photo! profile)]

    (t/testing "a failed job releases the input and leaves the profile"
      (let [{:keys [job-id params]} (make-job! (:id profile)
                                               "backend_tests/test_files/sample.png"
                                               "image/png")]
        (t/is (= 1 (jobs/fail th/*system* job-id {:type :internal
                                                  :code :boom
                                                  :hint "boom"})))
        (t/is (nil? (sto/get-object (storage) (get-in params [:input :id]))))
        (t/is (= (:id old) (:photo-id (profile-row (:id profile)))))))

    (t/testing "a cancelled job releases the input and leaves the profile"
      (let [{:keys [job-id params]} (make-job! (:id profile)
                                               "backend_tests/test_files/sample.png"
                                               "image/png")]
        (t/is (= 1 (jobs/cancel th/*system* job-id)))
        (t/is (nil? (sto/get-object (storage) (get-in params [:input :id]))))
        (t/is (= (:id old) (:photo-id (profile-row (:id profile)))))))))

(t/deftest profile-thumbnail-job-fails-when-the-result-is-expired
  (let [profile (make-profile)
        old     (add-old-photo! profile)
        {:keys [job-id params]} (make-job! (:id profile)
                                           "backend_tests/test_files/sample.png"
                                           "image/png")
        expired {:photo {:id    (uuid/next)
                         :uri   "https://example.com/assets/by-id/x"
                         :mtype "image/jpeg"
                         :size  1}}
        n       (jobs/complete th/*system* :job-id job-id :result expired)
        job     (jobs/get-job th/*system* job-id)]

    (t/is (= 0 n))
    (t/is (= "failed" (:status job)))
    (t/is (= :media-result-expired (:code (jobs/decode-job-error (:error job)))))
    (t/is (= (:id old) (:photo-id (profile-row (:id profile)))))
    (t/is (nil? (sto/get-object (storage) (get-in params [:input :id]))))))

(t/deftest profile-thumbnail-job-releases-the-temporaries-when-the-write-fails
  (let [profile (make-profile)
        old     (add-old-photo! profile)
        {:keys [job-id params]} (make-job! (:id profile)
                                           "backend_tests/test_files/sample.png"
                                           "image/png")
        result  (th/run-task! :profile-thumbnail params)
        cause   (with-redefs [sto/put-object! (fn [& _]
                                                (throw (ex-info "storage down" {})))]
                  (try
                    (jobs/complete th/*system* :job-id job-id :result result)
                    nil
                    (catch Throwable cause cause)))]

    (t/is (some? cause))
    (t/is (= (:id old) (:photo-id (profile-row (:id profile)))))

    (t/testing "the temporary result and the input are gone"
      (t/is (nil? (sto/get-object (storage) (get-in result [:photo :id]))))
      (t/is (nil? (sto/get-object (storage) (get-in params [:input :id])))))

    (t/testing "the executor can fail the job"
      (jobs/fail th/*system* job-id {:type :internal
                                     :code :processing-error
                                     :hint "storage down"})
      (t/is (= "failed" (:status (jobs/get-job th/*system* job-id)))))))
