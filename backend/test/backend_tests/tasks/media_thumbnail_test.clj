;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks.media-thumbnail-test
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

(defn- create-media-object!
  []
  (sto/put-object! (storage)
                   {::sto/content      (sto/content "image-bytes")
                    :content-type      "image/png"
                    :bucket            "file-media-object"}))

(defn- make-context
  []
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    {:profile profile
     :file    file}))

(defn- make-job!
  "Submit and claim a `:media-thumbnail` job for the media object, with a
  real input already materialized to `tempfile`."
  [media-id]
  (let [path   (th/tempfile "backend_tests/test_files/sample.png")
        ref    (mj/materialize-input th/*system* {:path path :mtype "image/png"})
        params {:input    ref
                :media-id media-id
                :format   :jpeg
                :quality  85
                :width    100
                :height   100}
        job-id (jobs/submit th/*system*
                            {::jobs/name   :media-thumbnail
                             ::jobs/params params
                             ::jobs/label  (mj/object-label media-id)})]
    (jobs/claim th/*system* job-id (:scheduled-at (jobs/get-job th/*system* job-id)))
    {:job-id job-id
     :params params}))

(defn- fmo
  [id]
  (th/db-get :file-media-object {:id id}))

(defn- capture-objects!
  "A `sto/put-object!` wrapper that records every object it creates, so the
  test can assert that one was deleted afterwards."
  []
  (let [original sto/put-object!
        created  (atom [])]
    {:created created
     :redef   (fn [storage params]
                (let [object (original storage params)]
                  (swap! created conj object)
                  object))}))

(t/deftest media-thumbnail-job-attaches-the-result-to-every-row
  (let [{:keys [file]} (make-context)
        media          (create-media-object!)
        fmo1           (th/create-file-media-object* {:file-id (:id file)
                                                      :media-id (:id media)})
        fmo2           (th/create-file-media-object* {:file-id (:id file)
                                                      :media-id (:id media)})
        {:keys [job-id params]} (make-job! (:id media))
        result         (th/run-task! :media-thumbnail params)
        n              (jobs/complete th/*system* :job-id job-id :result result)]

    (t/is (= 1 n))

    (t/testing "every row of the media object gets the same thumbnail"
      (let [row1 (fmo (:id fmo1))
            row2 (fmo (:id fmo2))]
        (t/is (uuid? (:thumbnail-id row1)))
        (t/is (= (:thumbnail-id row1) (:thumbnail-id row2)))
        (t/is (some? (sto/get-object (storage) (:thumbnail-id row1))))))

    (t/testing "the temporary result and the input are released"
      (t/is (nil? (sto/get-object (storage) (get-in result [:thumbnail :id]))))
      (t/is (nil? (sto/get-object (storage) (get-in params [:input :id])))))

    (t/testing "the job completed"
      (t/is (= "completed" (:status (jobs/get-job th/*system* job-id)))))))

(t/deftest media-thumbnail-job-writes-nothing-when-the-media-object-is-gone
  (let [{:keys [file]} (make-context)
        media          (create-media-object!)
        row            (th/create-file-media-object* {:file-id (:id file)
                                                      :media-id (:id media)})
        {:keys [job-id params]} (make-job! (:id media))
        result         (th/run-task! :media-thumbnail params)
        _              (th/db-force-delete :file-media-object {:id (:id row)})
        captured       (capture-objects!)]

    (t/is (= 1 (with-redefs [sto/put-object! (:redef captured)]
                 (jobs/complete th/*system* :job-id job-id :result result))))

    (t/testing "the artifact created by the hook is not left behind"
      (t/is (some? @(:created captured)))
      (t/is (nil? (sto/get-object (storage) (:id (last @(:created captured)))))))

    (t/testing "the input is released anyway"
      (t/is (nil? (sto/get-object (storage) (get-in params [:input :id])))))

    (t/is (= "completed" (:status (jobs/get-job th/*system* job-id))))))

(t/deftest media-thumbnail-job-is-idempotent
  (let [{:keys [file]} (make-context)
        media          (create-media-object!)
        row            (th/create-file-media-object* {:file-id (:id file)
                                                      :media-id (:id media)})

        first-job      (make-job! (:id media))
        first-result   (th/run-task! :media-thumbnail (:params first-job))
        _              (jobs/complete th/*system* :job-id (:job-id first-job) :result first-result)
        first-thumb    (:thumbnail-id (fmo (:id row)))

        second-job     (make-job! (:id media))
        second-result  (th/run-task! :media-thumbnail (:params second-job))
        captured       (capture-objects!)]

    (t/is (= 1 (with-redefs [sto/put-object! (:redef captured)]
                 (jobs/complete th/*system*
                                :job-id (:job-id second-job)
                                :result second-result))))

    (t/testing "the row keeps the thumbnail it already had"
      (t/is (uuid? first-thumb))
      (t/is (= first-thumb (:thumbnail-id (fmo (:id row))))))

    (t/testing "the redundant artifact is deleted"
      (t/is (some? @(:created captured)))
      (t/is (nil? (sto/get-object (storage) (:id (last @(:created captured)))))))))

(t/deftest media-thumbnail-job-fails-when-the-result-is-expired
  (let [{:keys [file]} (make-context)
        media          (create-media-object!)
        row            (th/create-file-media-object* {:file-id (:id file)
                                                      :media-id (:id media)})
        {:keys [job-id params]} (make-job! (:id media))
        expired        {:thumbnail {:id (uuid/next)
                                    :uri "https://example.com/assets/by-id/x"
                                    :mtype "image/jpeg"
                                    :size 1}}
        n              (jobs/complete th/*system* :job-id job-id :result expired)
        job            (jobs/get-job th/*system* job-id)]

    (t/is (= 0 n))
    (t/is (= "failed" (:status job)))
    (t/is (= :media-result-expired (:code (jobs/decode-job-error (:error job)))))
    (t/is (nil? (:thumbnail-id (fmo (:id row)))))

    (t/testing "the input is released by the failed hook"
      (t/is (nil? (sto/get-object (storage) (get-in params [:input :id])))))))

(t/deftest media-thumbnail-job-refuses-an-invalid-result
  (let [{:keys [file]} (make-context)
        media          (create-media-object!)
        row            (th/create-file-media-object* {:file-id (:id file)
                                                      :media-id (:id media)})
        {:keys [job-id params]} (make-job! (:id media))
        cause          (try
                         (jobs/complete th/*system* :job-id job-id
                                        :result {:thumbnail {:id "not-a-uuid"}})
                         nil
                         (catch Throwable cause cause))]

    (t/is (= :invalid-result (:code (ex-data cause))))
    (t/is (= "running" (:status (jobs/get-job th/*system* job-id))))
    (t/is (nil? (:thumbnail-id (fmo (:id row)))))
    (t/is (some? (sto/get-object (storage) (get-in params [:input :id])))
          "the hook never ran, so the input is still there")))

(t/deftest media-thumbnail-job-releases-the-temporaries-when-the-write-fails
  (let [{:keys [file]} (make-context)
        media          (create-media-object!)
        row            (th/create-file-media-object* {:file-id (:id file)
                                                      :media-id (:id media)})
        {:keys [job-id params]} (make-job! (:id media))
        result         (th/run-task! :media-thumbnail params)
        cause          (with-redefs [sto/put-object! (fn [& _]
                                                       (throw (ex-info "storage down" {})))]
                         (try
                           (jobs/complete th/*system* :job-id job-id :result result)
                           nil
                           (catch Throwable cause cause)))]

    (t/is (some? cause))
    (t/is (= "running" (:status (jobs/get-job th/*system* job-id)))
          "the writer propagates, so the executor fails the job")

    (t/testing "the temporary result and the input are gone"
      (t/is (nil? (sto/get-object (storage) (get-in result [:thumbnail :id]))))
      (t/is (nil? (sto/get-object (storage) (get-in params [:input :id])))))

    (t/testing "the executor fails the job"
      (jobs/fail th/*system* job-id {:type :internal
                                     :code :processing-error
                                     :hint "storage down"})
      (t/is (= "failed" (:status (jobs/get-job th/*system* job-id))))
      (t/is (nil? (:thumbnail-id (fmo (:id row))))))))

(t/deftest store-result-streams-the-artifact-without-reading-it-into-memory
  (let [object (sto/put-object! (storage)
                                {::sto/content (sto/content "artifact")
                                 :content-type "image/png"
                                 :bucket       "tempfile"})]
    (with-redefs [sto/get-object-bytes
                  (fn [& _]
                    (throw (ex-info "the artifact must not be read in memory" {})))]
      (let [result (mj/store-result th/*system* (:id object)
                                    "file-media-object"
                                    {:content-type "image/png"})]
        (t/is (uuid? (:id result)))
        (t/is (some? (sto/get-object (storage) (:id result))))))))

(t/deftest media-thumbnail-complete-decodes-the-wire-result
  ;; The worker sends the result as plain JSON, with the artifact id as text.
  ;; The substrate decodes it with the job-def codec before validating and
  ;; storing it, so the hook and the row see the typed value.
  (let [{:keys [file]} (make-context)
        media          (create-media-object!)
        row            (th/create-file-media-object* {:file-id (:id file)
                                                      :media-id (:id media)})
        {:keys [job-id params]} (make-job! (:id media))
        result         (th/run-task! :media-thumbnail params)
        thumb          (:thumbnail result)
        wire           {:thumbnail {:id    (str (:id thumb))
                                    :uri   (:uri thumb)
                                    :mtype (:mtype thumb)
                                    :size  (:size thumb)}}]

    (t/is (= 1 (jobs/complete th/*system* :job-id job-id :result wire)))

    (t/testing "the stored result is the typed value, not the wire text"
      (let [job (jobs/get-job th/*system* job-id)]
        (t/is (= "completed" (:status job)))
        (t/is (uuid? (get-in job [:result :thumbnail :id])))
        (t/is (= (:id thumb) (get-in job [:result :thumbnail :id])))))

    (t/testing "the hook stored the artifact"
      (t/is (uuid? (:thumbnail-id (fmo (:id row))))))))
