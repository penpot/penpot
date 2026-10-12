;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-security-test
  "Tests for the nexus file-security backend prerequisite: the
  `get-file-for-analysis` management method, the semantic projection,
  and the audit prop coverage the `:local` consumer schedules from."
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.management.files :as mgmt.files]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.request]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; --- Projection unit tests (pure, no database)

(def ^:private page-id (uuid/random))
(def ^:private frame-id (uuid/random))
(def ^:private text-id (uuid/random))
(def ^:private link-id (uuid/random))

(defn- test-data
  [objects]
  {:pages [page-id]
   :pages-index {page-id {:id page-id
                          :name "Page 1"
                          :objects objects}}})

(t/deftest projection-empty-file
  (let [projection (mgmt.files/file->projection (test-data {}))]
    (t/is (= 1 (:version projection)))
    (t/is (= [{:id (str page-id) :name "Page 1"}] (:pages projection)))
    (t/is (= [] (:frames projection)))
    (t/is (= {:items [] :truncated? false} (:texts projection)))
    (t/is (= {:items [] :truncated? false} (:links projection)))
    (t/is (= {:items [] :truncated? false} (:interactions projection)))))

(t/deftest projection-picks-frames-text-links-and-interactions
  (let [data       (test-data
                    {frame-id {:id frame-id :name "Frame" :type :frame}
                     text-id {:id text-id :name "Text" :type :text
                              :content {:type "root"
                                        :children [{:type "paragraph"
                                                    :children [{:text "hello"}
                                                               {:text " world"}]}]}}
                     link-id {:id link-id :name "Button" :type :rect
                              :interactions [{:event-type :click
                                              :action-type :open-url
                                              :url "https://phish.example/login"}
                                             {:event-type :click
                                              :action-type :navigate
                                              :destination page-id}
                                             ;; No event, cannot trigger:
                                             ;; collected as link, never
                                             ;; as interaction.
                                             {:action-type :open-url
                                              :url "https://quiet.example"}]}})
        projection (mgmt.files/file->projection data)]
    (t/is (= [{:id (str frame-id) :page-id (str page-id) :name "Frame"}]
             (:frames projection)))
    (t/is (= {:items [{:shape-id (str text-id)
                       :page-id (str page-id)
                       :text "hello world"}]
              :truncated? false}
             (:texts projection)))
    (t/is (= {:items [{:shape-id (str link-id)
                       :page-id (str page-id)
                       :url "https://phish.example/login"}
                      {:shape-id (str link-id)
                       :page-id (str page-id)
                       :url "https://quiet.example"}]
              :truncated? false}
             (:links projection)))
    (t/is (= {:items [{:shape-id (str link-id)
                       :page-id (str page-id)
                       :event "click"
                       :action "open-url"
                       :target "https://phish.example/login"}
                      {:shape-id (str link-id)
                       :page-id (str page-id)
                       :event "click"
                       :action "navigate"
                       :target (str page-id)}]
              :truncated? false}
             (:interactions projection)))))

(t/deftest projection-skips-blank-text-and-corrupt-content
  (let [bad-id   (uuid/random)
        projection (mgmt.files/file->projection
                    (test-data
                     {text-id {:id text-id :type :text
                               :content {:type "root"
                                         :children [{:type "paragraph"
                                                     :children [{:text "   "}]}]}}
                      bad-id {:id bad-id :type :text
                              :content {:type "root" :children 42}}}))]
    ;; Blank text contributes nothing; corrupt content skips the shape
    ;; instead of failing the file.
    (t/is (= {:items [] :truncated? false} (:texts projection)))))

(t/deftest projection-truncates-links
  (let [objects (into {}
                      (map (fn [i]
                             (let [id (uuid/random)]
                               [id {:id id :type :rect
                                    :interactions [{:event-type :click
                                                    :action-type :open-url
                                                    :url (str "https://example.com/" i)}]}])))
                      (range (inc mgmt.files/max-links)))
        projection (mgmt.files/file->projection (test-data objects))]
    (t/is (= mgmt.files/max-links (count (get-in projection [:links :items]))))
    (t/is (true? (get-in projection [:links :truncated?])))
    (t/is (false? (get-in projection [:interactions :truncated?])))))

;; --- Management method (through the worker stack)

(def text-content
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :children [{:text "cheap meds"}]}]}]})

(defn- create-file!
  [profile-id project-id]
  (let [out (th/command! {::th/type :create-file
                          ::rpc/profile-id profile-id
                          :project-id project-id
                          :name "Shop"
                          :is-shared false})]
    (t/is (nil? (:error out)))
    (get-in out [:result :id])))

(defn- file-page-id
  [file-id]
  (get-in (bfc/get-file th/*system* file-id) [:data :pages 0]))

(defn- update-file!
  [profile-id file-id page-id changes revn]
  (let [out (th/command! {::th/type :update-file
                          ::rpc/profile-id profile-id
                          :id file-id
                          :session-id (uuid/random)
                          :revn revn
                          :vern 0
                          :features cfeat/supported-features
                          :changes changes})]
    (t/is (nil? (:error out)))
    (:result out)))

(defn- add-text-shape-change
  [page-id shape-id]
  [{:type :add-obj
    :page-id page-id
    :id shape-id
    :parent-id uuid/zero
    :frame-id uuid/zero
    :components-v2 true
    :obj (cts/setup-shape
          {:id shape-id
           :name "Buy now"
           :frame-id uuid/zero
           :parent-id uuid/zero
           :type :text
           :content text-content})}])

(t/deftest get-file-for-analysis-returns-projection
  (let [profile (th/create-profile* 1 {:is-active true})
        team    (th/create-team* 1 {:profile-id (:id profile)})
        project (th/create-project* 1 {:profile-id (:id profile)
                                       :team-id (:id team)})
        file-id (create-file! (:id profile) (:id project))
        page-id (file-page-id file-id)]
    ;; One edit so revn moves past zero and the file has content.
    (update-file! (:id profile) file-id page-id
                  (add-text-shape-change page-id (uuid/random))
                  0)
    (let [out (th/management-command! {::th/type :get-file-for-analysis
                                       :file-id file-id})]
      (t/is (nil? (:error out)))
      (let [result (:result out)]
        (t/is (= file-id (:id result)))
        (t/is (= 1 (:revn result)))
        (t/is (some? (:modified-at result)))
        (t/is (= (:id project) (:project-id result)))
        (t/is (= (:id team) (:team-id result)))
        (t/is (false? (:has-share-link result)))
        (t/is (= mgmt.files/projection-version (:projection-version result)))
        (t/is (= "cheap meds"
                 (-> result :projection :texts :items first :text)))))
    ;; A share link flips the share state, no re-edit needed.
    (th/command! {::th/type :create-share-link
                  ::rpc/profile-id (:id profile)
                  :file-id file-id
                  :pages #{page-id}
                  :who-comment "all"
                  :who-inspect "all"})
    (let [out (th/management-command! {::th/type :get-file-for-analysis
                                       :file-id file-id})]
      (t/is (nil? (:error out)))
      (t/is (true? (:has-share-link (:result out)))))))

(t/deftest get-file-for-analysis-rejects-unknown-file
  (let [out (th/management-command! {::th/type :get-file-for-analysis
                                     :file-id (uuid/random)})]
    (t/is (some? (:error out)))
    (t/is (= :not-found (th/ex-type (:error out))))))

;; --- Audit prop coverage (through the commands, into real events)
;;
;; NOTE: the test system neutralizes `audit/submit`, so no audit row
;; is ever written. The observable contract of a command is the
;; metadata it sets on its result, which `prepare-rpc-event` turns
;; into the stored event: feed one into the other with the real
;; method metadata and assert the final props.

(def mock-request
  (reify
    yetti.request/IRequest
    (get-header [_ _] nil)
    (remote-addr [_] "127.0.0.1")))

(defn- event-for
  "Run a main RPC command and return the audit event it would emit."
  [type params]
  (let [[mdata _] (get-in th/*system* [:app.rpc/methods type])
        result    (th/command-raw! (assoc params ::th/type type))
        params    (-> params
                      (assoc ::rpc/request-id (uuid/next)
                             ::rpc/request-at (ct/now))
                      (with-meta {:app.http/request mock-request}))
        ;; In production the caller assocs the module before
        ;; preparing the event; without it every name would come out
        ;; with a leading dash.
        cfg       (assoc th/*system* ::rpc/module "main")]
    (audit/prepare-rpc-event cfg mdata params result)))

(t/deftest get-file-for-analysis-emits-audit-event
  ;; Content reads stay traceable: with the skip gone, the call flows
  ;; through `wrap-audit` like any other management method. `wrap-audit`
  ;; itself is fixed at system init (no audit flags there), so assert the
  ;; metadata flip plus the event shape through `prepare-rpc-event` with
  ;; the real method metadata.
  (let [profile (th/create-profile* 1 {:is-active true})
        team    (th/create-team* 1 {:profile-id (:id profile)})
        project (th/create-project* 1 {:profile-id (:id profile)
                                       :team-id (:id team)})
        file-id (create-file! (:id profile) (:id project))
        [mdata _] (get-in th/*system* [:app.rpc/management-methods :get-file-for-analysis])]
    (t/is (not (::audit/skip mdata)))
    (let [out    (th/management-command! {::th/type :get-file-for-analysis
                                          :file-id file-id})
          result (:result out)
          params (-> {:file-id file-id}
                     (assoc ::rpc/request-id (uuid/next)
                            ::rpc/request-at (ct/now))
                     (with-meta {:app.http/request mock-request}))
          cfg    (assoc th/*system* ::rpc/module "management")
          event  (audit/prepare-rpc-event cfg mdata params result)]
      (t/is (nil? (:error out)))
      (t/is (= "management-get-file-for-analysis" (:name event)))
      (t/is (= file-id (get-in event [:props :file-id])))
      (t/is (= (:id team) (get-in event [:props :team-id]))))))

(t/deftest file-events-carry-scheduling-props
  (let [profile (th/create-profile* 2 {:is-active true})
        team    (th/create-team* 2 {:profile-id (:id profile)})
        project (th/create-project* 2 {:profile-id (:id profile)
                                       :team-id (:id team)})
        base    {::rpc/profile-id (:id profile)}]
    (t/testing "create-file"
      (let [event (event-for :create-file
                             (assoc base
                                    :project-id (:id project)
                                    :name "Shop"
                                    :is-shared false))
            props (:props event)]
        (t/is (= "create-file" (:name event)))
        (t/is (some? (:id props)))
        (t/is (= "Shop" (:name props)))
        (t/is (some? (:created-at props)))
        (t/is (some? (:modified-at props)))
        (t/is (= 0 (:revn props)))))
    (t/testing "update-file"
      ;; A file to edit, created through the command so the event
      ;; below belongs to a real row.
      (let [created (event-for :create-file
                               (assoc base
                                      :project-id (:id project)
                                      :name "Edit me"
                                      :is-shared false))
            file-id (get-in created [:props :id])
            page-id (file-page-id file-id)
            shape-id (uuid/random)
            event   (event-for :update-file
                               (assoc base
                                      :id file-id
                                      :session-id (uuid/random)
                                      :revn 0
                                      :vern 0
                                      :features cfeat/supported-features
                                      :changes [{:type :add-obj
                                                 :page-id page-id
                                                 :id shape-id
                                                 :parent-id uuid/zero
                                                 :frame-id uuid/zero
                                                 :components-v2 true
                                                 :obj (cts/setup-shape
                                                       {:id shape-id
                                                        :name "rect"
                                                        :frame-id uuid/zero
                                                        :parent-id uuid/zero
                                                        :type :rect})}]))
            props   (:props event)]
        (t/is (= "update-file" (:name event)))
        (t/is (= file-id (:id props)))
        (t/is (= 1 (:revn props)))
        (t/is (some? (:modified-at props)))))
    (t/testing "share-link create and delete"
      (let [created  (event-for :create-file
                                (assoc base
                                       :project-id (:id project)
                                       :name "Shared"
                                       :is-shared false))
            file-id  (get-in created [:props :id])
            page-id  (file-page-id file-id)
            event    (event-for :create-share-link
                                (assoc base
                                       :file-id file-id
                                       :pages #{page-id}
                                       :who-comment "all"
                                       :who-inspect "all"))
            props    (:props event)
            slink-id (:share-id props)]
        (t/is (= "create-share-link" (:name event)))
        (t/is (= file-id (:file-id props)))
        (t/is (some? slink-id))
        (let [event (event-for :delete-share-link
                               (assoc base :id slink-id))
              props (:props event)]
          (t/is (= "delete-share-link" (:name event)))
          (t/is (= file-id (:file-id props)))
          (t/is (= slink-id (:share-id props))))))
    (t/testing "delete-file"
      (let [created (event-for :create-file
                               (assoc base
                                      :project-id (:id project)
                                      :name "Doomed"
                                      :is-shared false))
            file-id (get-in created [:props :id])
            event   (event-for :delete-file
                               (assoc base :id file-id))
            props   (:props event)]
        (t/is (= "delete-file" (:name event)))
        (t/is (= file-id (:id props)))
        (t/is (some? (:deleted-at props)))))))
