;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.file-revision-test
  "The payload the `update-file` flow stores for every revision.

  A revision keeps its metadata row in `file_change` and, from this change
  on, its content in a `file_data` row of type `revision` that shares the
  id and the deletion schedule. The payload holds the forward changes of the
  save and their inverse; the column `file_change.changes` is still written
  for the readers deployed today."
  (:require
   [app.common.features :as cfeat]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- update-file!
  [& {:keys [profile-id file-id changes undo-changes revn vern]
      :or {revn 0 vern 0}}]
  (->> (cond-> {::th/type :update-file
                ::rpc/profile-id profile-id
                :id file-id
                :session-id (uuid/random)
                :revn revn
                :vern vern
                :features cfeat/supported-features
                :changes changes}

         (some? undo-changes)
         (assoc :undo-changes undo-changes))

       (th/command!)))

(defn- revision-row
  "The revision the save wrote: the `file_change` row of `revn` with no
  label. Rows are born with a future deletion date, so the query must keep
  deleted rows visible."
  [file-id revn]
  (->> (th/db-query :file-change {:file-id file-id} {::db/remove-deleted false})
       (filter #(and (= revn (:revn %)) (nil? (:label %))))
       (first)))

(defn- payload-row
  [file-id revision-id]
  (th/db-get :file-data
             {:file-id file-id :id revision-id :type "revision"}
             {::db/remove-deleted false}))

(defn- create-file
  []
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    [profile file]))

(t/deftest stores-both-change-lists-in-the-revision-payload
  (let [[profile file] (create-file)
        page-id       (uuid/next)
        redo          [{:type :add-page :id page-id :name "page"}]
        undo          [{:type :del-page :id page-id}]
        out           (update-file! :profile-id (:id profile)
                                    :file-id (:id file)
                                    :changes redo
                                    :undo-changes undo)
        revision      (revision-row (:id file) 1)
        payload       (payload-row (:id file) (:id revision))]

    (t/is (nil? (:error out)))
    (t/is (some? revision))
    (t/is (some? payload))

    (t/testing "the payload shares the id and the schedule of the revision"
      (t/is (= (:id revision) (:id payload)))
      (t/is (= (:deleted-at revision) (:deleted-at payload)))
      (t/is (some? (:deleted-at payload))))

    (t/testing "the payload holds the forward changes and their inverse"
      (t/is (= {:changes redo :undo-changes undo}
               (blob/decode (:data payload)))))

    (t/testing "the column keeps the forward changes for the deployed readers"
      (t/is (= redo (blob/decode (:changes revision)))))))

(t/deftest stores-an-empty-undo-list-when-the-request-omits-it
  (let [[profile file] (create-file)
        page-id       (uuid/next)
        redo          [{:type :add-page :id page-id :name "page"}]
        out           (update-file! :profile-id (:id profile)
                                    :file-id (:id file)
                                    :changes redo)
        revision      (revision-row (:id file) 1)
        payload       (payload-row (:id file) (:id revision))]

    (t/is (nil? (:error out)))
    (t/is (some? payload))
    (t/is (= {:changes redo :undo-changes []}
             (blob/decode (:data payload))))))

(t/deftest rejects-an-undo-list-that-does-not-conform
  (let [[profile file] (create-file)
        page-id       (uuid/next)
        out           (update-file! :profile-id (:id profile)
                                    :file-id (:id file)
                                    :changes [{:type :add-page :id page-id :name "page"}]
                                    :undo-changes [{:type :mod-obj}])]

    (t/is (th/ex-of-type? (:error out) :validation))
    (t/is (nil? (revision-row (:id file) 1)))))
