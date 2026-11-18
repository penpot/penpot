;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-file-catch-up-test
  "The save answer names the revisions a client lacks, and the catch-up read
  serves their changes, or a reload when the stored rows cannot."
  (:require
   [app.common.features :as cfeat]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- create-file
  []
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]
    {:profile-id (:id profile)
     :file-id    (:id file)
     :page-id    (-> file :data :pages first)}))

(defn- save!
  "Saves a new rect from `session-id` at the client revision `revn`, and
  returns the answer with the changes it sent."
  [{:keys [profile-id file-id page-id]} session-id revn]
  (let [changes [(th/add-rect-change page-id (uuid/next))]
        out     (th/command! {::th/type :update-file
                              ::rpc/profile-id profile-id
                              :id file-id
                              :session-id session-id
                              :revn revn
                              :vern 0
                              :features cfeat/supported-features
                              :changes changes})]
    (t/is (nil? (:error out)))
    (assoc (:result out) ::changes changes)))

(defn- catch-up
  [{:keys [profile-id file-id]} since]
  (let [out (th/command! {::th/type :get-file-changes
                          ::rpc/profile-id profile-id
                          :file-id file-id
                          :since since})]
    (t/is (nil? (:error out)))
    (:result out)))

(defn- age-rows!
  "Moves the creation of every revision row of the file `hours` back."
  [{:keys [file-id]} hours]
  (th/db-exec! ["update file_change set created_at = created_at - ?::interval
                  where file_id = ? and label is null"
                (str hours " hours") file-id]))

(t/deftest the-save-answer-names-the-revisions-above-the-client
  (let [ctx  (create-file)
        a    (uuid/next)
        b    (uuid/next)
        _    (save! ctx a 0)
        _    (save! ctx a 1)
        out  (save! ctx b 0)]
    (t/is (= 2 (:revn out)) "the revision before this save")
    (t/is (= [{:revn 1 :session-id a}
              {:revn 2 :session-id a}
              {:revn 3 :session-id b}]
             (:lagged out))
          "revision and session only, in order, the save's own last")
    (t/is (not (contains? out :reload)))))

(t/deftest the-save-answer-says-reload-past-the-row-cap
  (let [ctx (create-file)
        a   (uuid/next)
        _   (save! ctx a 0)
        _   (save! ctx a 1)]
    (with-redefs [cf/config (assoc cf/config :lagged-row-cap 2)]
      (t/is (= [{:revn 3 :session-id a}] (:lagged (save! ctx a 2)))
            "one row is within the cap")
      (let [out (save! ctx (uuid/next) 0)]
        (t/is (true? (:reload out)))
        (t/is (= [] (:lagged out)))))))

(t/deftest a-client-23-hours-behind-catches-up
  (let [ctx   (create-file)
        a     (uuid/next)
        b     (uuid/next)
        save1 (save! ctx a 0)
        save2 (save! ctx b 1)
        save3 (save! ctx a 2)
        _     (age-rows! ctx 23)
        out   (catch-up ctx 0)]
    (t/is (= 3 (:revn out)))
    (t/is (not (contains? out :reload)))
    (t/is (= [{:revn 1 :session-id a :changes (::changes save1)}
              {:revn 2 :session-id b :changes (::changes save2)}
              {:revn 3 :session-id a :changes (::changes save3)}]
             (:changes out)))
    (t/is (= [3] (map :revn (:changes (catch-up ctx 2))))
          "a client part of the way gets the rest")
    (t/is (= {:revn 3 :changes []} (catch-up ctx 3))
          "a client at the head gets nothing")))

(t/deftest a-client-past-the-window-reloads
  (let [ctx (create-file)]
    (save! ctx (uuid/next) 0)
    (save! ctx (uuid/next) 1)
    (age-rows! ctx 25)
    (t/is (= {:revn 2 :reload true} (catch-up ctx 0)))))

(t/deftest a-gap-heavier-than-the-size-cap-reloads
  (let [ctx (create-file)]
    (save! ctx (uuid/next) 0)
    (with-redefs [cf/config (assoc cf/config :catch-up-max-size 1)]
      (t/is (= {:revn 1 :reload true} (catch-up ctx 0))))))

(t/deftest a-revision-without-a-row-reloads
  (let [{:keys [file-id] :as ctx} (create-file)]
    (save! ctx (uuid/next) 0)
    (save! ctx (uuid/next) 1)
    (save! ctx (uuid/next) 2)
    (t/testing "a row expired from the middle of the gap"
      (th/db-exec! ["delete from file_change where file_id = ? and revn = 2" file-id])
      (t/is (= {:revn 3 :reload true} (catch-up ctx 0)))
      (t/is (= [3] (map :revn (:changes (catch-up ctx 2))))
            "the part above the hole is still served"))
    (t/testing "a write that stored no row at the head"
      (th/db-update! :file {:revn 4} {:id file-id})
      (t/is (= {:revn 4 :reload true} (catch-up ctx 2))))))

(t/deftest the-catch-up-needs-read-access
  (let [ctx      (create-file)
        stranger (th/create-profile* 2)
        _        (save! ctx (uuid/next) 0)
        out      (th/command! {::th/type :get-file-changes
                               ::rpc/profile-id (:id stranger)
                               :file-id (:file-id ctx)
                               :since 0})]
    (t/is (= :not-found (th/ex-type (:error out))))))
