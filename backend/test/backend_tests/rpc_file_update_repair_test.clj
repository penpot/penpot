;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-file-update-repair-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.transit :as transit]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.http :as http]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.metrics :as mtx]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files-update :as files-update]
   [app.srepl.procs.file-repair :as file-repair]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.adapter :as yt])
  (:import
   io.prometheus.client.CollectorRegistry
   io.undertow.Undertow
   io.undertow.Undertow$ListenerInfo
   java.net.http.HttpClient
   java.net.http.HttpRequest
   java.net.http.HttpRequest$BodyPublishers
   java.net.http.HttpResponse$BodyHandlers
   java.net.InetSocketAddress
   java.net.URI
   java.time.Duration
   org.postgresql.util.PSQLException
   org.postgresql.util.PSQLState))

(t/use-fixtures :once (t/compose-fixtures
                       (partial th/init-config [:enable-telemetry])
                       th/init-system))
(t/use-fixtures :each th/database-reset)

(defn- with-repair-flags
  [f]
  (with-redefs [cf/flags (conj cf/flags :file-validation)]
    (f)))

(defn- with-full-repair-flags
  [f]
  (with-redefs [cf/flags (conj cf/flags :file-validation :file-schema-validation)]
    (f)))

(defn- repair-count
  [outcome]
  (or (.getSampleValue ^CollectorRegistry
       (mtx/get-registry (::mtx/metrics th/*system*))
                       "penpot_rpc_update_file_repair_total"
                       (into-array String ["outcome"])
                       (into-array String [(name outcome)]))
      0.0))

(defn- add-rect
  [page-id shape-id]
  {:type :add-obj
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
          :type :rect})})

(defn- set-attr
  [page-id shape-id attr val]
  {:type :mod-obj
   :id shape-id
   :page-id page-id
   :operations [{:type :set :attr attr :val val}]})

(defn- update-file
  [profile-id file-id revn changes & {:keys [skip-validate session-id]
                                      :or {session-id (uuid/random)}}]
  (th/command! (cond-> {::th/type :update-file
                        ::rpc/profile-id profile-id
                        :id file-id
                        :session-id session-id
                        :revn revn
                        :vern 0
                        :features cfeat/supported-features
                        :changes changes}
                 skip-validate (assoc :skip-validate true))))

(defn- stored-file
  [file-id]
  (bfc/get-file th/*system* file-id :realize? true))

(defn- change-rows
  [file-id]
  (->> (th/db-query :file-change {:file-id file-id} {::db/remove-deleted false})
       (filter (fn [row]
                 (and (some? (:changes row))
                      (nil? (:label row)))))
       (sort-by :created-at)
       (vec)))

(defn- label-rows
  [file-id]
  (->> (th/db-query :file-change {:file-id file-id} {::db/remove-deleted false})
       (filter :label)
       (vec)))

(defn- pointer-map-in?
  [x]
  (cond
    (pmap/pointer-map? x) true
    (map? x) (boolean (some pointer-map-in? (vals x)))
    (sequential? x) (boolean (some pointer-map-in? x))
    :else false))

(defn- setup-file
  [& {:keys [is-shared] :or {is-shared false}}]
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared is-shared})
        page-id  (first (get-in file [:data :pages]))
        shape-a  (uuid/next)
        shape-b  (uuid/next)
        out      (update-file (:id profile) (:id file) 0
                              [(add-rect page-id shape-a)
                               (add-rect page-id shape-b)])]
    (t/is (th/success? out))
    {:profile profile
     :file-id (:id file)
     :page-id page-id
     :shape-a shape-a
     :shape-b shape-b
     :revn (:revn (stored-file (:id file)))}))

(defn- break-frame
  [{:keys [profile file-id page-id shape-a revn]}]
  (let [out (update-file (:id profile) file-id revn
                         [(set-attr page-id shape-a :frame-id (uuid/next))]
                         :skip-validate true)]
    (t/is (th/success? out))
    (stored-file file-id)))

(defn- update-file-v2
  [{:keys [profile file-id]} revn commits & {:as opts}]
  (th/command! (merge {::th/type :update-file-v2
                       ::rpc/profile-id (:id profile)
                       :id file-id
                       :session-id (uuid/next)
                       :revn revn
                       :vern 0
                       :features cfeat/supported-features
                       :repair-in-response true
                       :commits commits}
                      opts)))

(defn- commit
  [redo undo]
  {:commit-id (uuid/next) :redo-changes redo :undo-changes undo})

(defn- create-image
  [file-id]
  (let [storage-id (uuid/next)]
    (th/db-insert! :storage-object {:id storage-id :size 100 :backend "test"})
    (th/create-file-media-object* {:file-id file-id :media-id storage-id :mtype "image/jpeg"})))

(defn- image-fills
  [image]
  [{:fill-opacity 1 :fill-image (select-keys image [:id :width :height :mtype])}])

(t/deftest opt-in-repair-stores-two-replayable-revisions
  (with-full-repair-flags
    (fn []
      (let [{:keys [file-id page-id shape-b] :as ctx} (setup-file :is-shared true)
            broken   (break-frame ctx)
            n        (:revn broken)
            rename   (set-attr page-id shape-b :name "edited")
            redo     [{:type :mod-page :id page-id :name "edited page"} rename]
            undo     [(set-attr page-id shape-b :name "rect")]
            messages (atom [])
            audited  (atom nil)
            session  (uuid/next)
            out      (with-redefs [audit/submit (fn [_ event] (reset! audited event))
                                   mbus/pub! (fn [_ & {:keys [message]}]
                                               (swap! messages conj message))]
                       (update-file-v2 ctx n [(commit redo undo)] :session-id session))
            result   (:result out)
            [u r]    (->> (change-rows file-id)
                          (filter #(> (:revn %) n))
                          (sort-by :revn))
            payload  (fn [row]
                       (let [payload (th/db-get :file-data {:file-id file-id :id (:id row) :type "revision"}
                                                {::db/remove-deleted false})]
                         (t/is (= (:deleted-at row) (:deleted-at payload)))
                         (blob/decode (:data payload))))
            [file-msg lib-msg] @messages]
        (t/is (th/success? out))
        (t/is (= n (:revn result)))
        (t/is (= (+ n 2) (:saved-revn result)))
        (t/is (= {:id (:id r) :revn (+ n 2) :changes (blob/decode (:changes r))}
                 (:repair result)))
        (t/is (= [(:id u) (:id r)] (mapv :id (:lagged result))))
        (t/is (= {:redo-changes redo :undo-changes undo} (payload u)))
        (t/is (= {:redo-changes (get-in result [:repair :changes]) :undo-changes []} (payload r)))
        (t/is (= (:id u) (get-in @audited [:props :file-change-id])))
        (t/is (= 2 (count @messages)))
        (t/is (= [:file-change :library-change] (mapv :type @messages)))
        (t/is (= (into redo (get-in result [:repair :changes])) (:changes file-msg)))
        (t/is (= (into [rename] (get-in result [:repair :changes])) (:changes lib-msg)))
        (t/is (= [(:id u) (:id r)] (:revision-ids file-msg)))
        (doseq [msg [file-msg lib-msg]]
          (t/is (= {:revn (+ n 2) :repair-base-revn n :repaired true :session-id session :vern 0}
                   (select-keys msg [:revn :repair-base-revn :repaired :session-id :vern]))))))))

(t/deftest failed-transaction-does-not-publish-or-keep-a-repair
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
            source   (th/create-file* 2 {:profile-id (:id profile)
                                         :project-id (:default-project-id profile)})
            image    (create-image (:id source))
            broken   (break-frame ctx)
            before   (set (map :id (change-rows file-id)))
            labels   (set (map :id (label-rows file-id)))
            payloads #(set (map (juxt :id :type)
                                (th/db-query :file-data {:file-id file-id}
                                             {::db/remove-deleted false})))
            data-ids (payloads)
            messages (atom [])
            prepared (atom nil)
            after    db/after-commit
            fault    (ex-info "failure after notification registration" {})
            out      (with-redefs [mbus/pub! (fn [_ & {:keys [message]}]
                                               (swap! messages conj message))
                                   db/after-commit (fn [callback]
                                                     (after callback)
                                                     (let [conn db/*after-commit-conn*]
                                                       (reset! prepared
                                                               [(:revn (db/get conn :file {:id file-id}))
                                                                (count (db/query conn :file-change
                                                                                 {:file-id file-id}
                                                                                 {::db/remove-deleted false}))
                                                                (count (db/query conn :file-data
                                                                                 {:file-id file-id :type "revision"}
                                                                                 {::db/remove-deleted false}))]))
                                                     (throw fault))]
                       (update-file-v2 ctx (:revn broken)
                                       [(commit [(set-attr page-id shape-b :fills (image-fills image))] [])]))]
        (t/is (identical? fault (:error out)))
        (t/is (= [(+ 2 (:revn broken)) (+ 3 (count before) (count labels)) (+ 2 (count before))]
                 @prepared))
        (t/is (empty? @messages))
        (t/is (= (select-keys broken [:revn :data])
                 (select-keys (stored-file file-id) [:revn :data])))
        (t/is (= before (set (map :id (change-rows file-id)))))
        (t/is (= labels (set (map :id (label-rows file-id)))))
        (t/is (= data-ids (payloads)))
        (t/is (empty? (th/db-query :file-media-object {:file-id file-id})))))))

(t/deftest repair-does-not-hide-infrastructure-failures
  (with-repair-flags
    (fn []
      (let [{:keys [file-id page-id shape-b] :as ctx} (setup-file)
            broken   (break-frame ctx)
            n        (:revn broken)
            edit     (commit [(set-attr page-id shape-b :name "edited")] [])
            repair   cfr/repair-file
            messages (atom [])]
        (doseq [fault [(java.sql.SQLTransientException. "database unavailable")
                       (java.io.IOException. "fragment stream interrupted")
                       (InterruptedException. "interrupted")
                       (ex-info "fragment not found" {:type :internal :code :fragment-not-found})
                       (ex-info "fragment not found" {:type :not-found :code :object-not-found})]]
          (let [out (with-redefs [cfr/repair-file (fn [& _] (throw fault))
                                  mbus/pub! (fn [_ & {:keys [message]}]
                                              (swap! messages conj message))]
                      (update-file-v2 ctx n [edit]))]
            (t/is (identical? fault (:error out)))
            (t/is (nil? (:result out)))))
        (t/testing "even an infrastructure failure in the optional reverse-order repair aborts"
          (let [calls (atom 0)
                fault (java.io.IOException. "repair stream interrupted")
                out   (with-redefs [cfr/repair-file (fn [& args]
                                                      (if (= 2 (swap! calls inc))
                                                        (throw fault)
                                                        (apply repair args)))
                                    mbus/pub! (fn [_ & {:keys [message]}]
                                                (swap! messages conj message))]
                        (update-file-v2 ctx n [edit]))]
            (t/is (identical? fault (:error out)))))
        (t/is (empty? @messages))
        (t/is (empty? (filter #(> (:revn %) n) (change-rows file-id))))
        (t/is (not-any? #(= (str "internal/repair/" n) (:label %)) (label-rows file-id)))
        (t/is (= n (:revn (stored-file file-id))))))))

(t/deftest does-not-repair-new-damage-next-to-preexisting-damage
  (with-repair-flags
    (fn []
      (let [{:keys [file-id page-id shape-a shape-b] :as ctx} (setup-file)
            broken   (break-frame ctx)
            n        (:revn broken)
            bad-edit (commit [(set-attr page-id shape-b :frame-id (uuid/next))] [])
            page-2   (uuid/next)
            good     (commit [{:type :add-page :id page-2 :name "valid prefix"}] [])
            messages (atom [])]
        (with-redefs [mbus/pub! (fn [_ & {:keys [message]}]
                                  (swap! messages conj message))]
          (t/testing "a rejected first commit leaves the preexisting damage and writes nothing"
            (let [result (:result (update-file-v2 ctx n [bad-edit]))]
              (t/is (= [(:commit-id bad-edit)] (get-in result [:rejected :commit-ids])))
              (t/is (= #{:revn :lagged :rejected} (set (keys result))))
              (t/is (empty? @messages))
              (t/is (empty? (filter #(> (:revn %) n) (change-rows file-id))))
              (t/is (not-any? #(= (str "internal/repair/" n) (:label %)) (label-rows file-id)))))
          (t/testing "only the valid prefix on another page is saved"
            (let [result (:result (update-file-v2 ctx n [good bad-edit]))
                  stored (stored-file file-id)
                  row    (last (change-rows file-id))]
              (t/is (= [(:commit-id bad-edit)] (get-in result [:rejected :commit-ids])))
              (t/is (= (inc n) (:saved-revn result)))
              (t/is (not (contains? result :repair)))
              (t/is (= [(select-keys row [:id :revn])]
                       (mapv #(select-keys % [:id :revn]) (:lagged result))))
              (t/is (= [[(:id row)]] (mapv :revision-ids @messages)))
              (t/is (= "valid prefix" (get-in stored [:data :pages-index page-2 :name])))
              (t/is (= [shape-a] (mapv :shape-id (cfv/validate-file stored {})))))))))))

(t/deftest falls-back-when-a-move-would-be-ignored-on-the-damaged-copy
  (thi/reset-idmap!)
  (with-repair-flags
    (fn []
      (let [{:keys [file-id page-id] :as ctx} (setup-file)
            broken   (-> (with-meta (stored-file file-id) {:current-page-id page-id})
                         (tho/add-simple-component :component :main :child)
                         (thc/instantiate-component :component :copy)
                         (ths/update-shape :copy :component-id (uuid/next)))
            copy-id  (thi/id :copy)
            child-id (first (get-in broken [:data :pages-index page-id :objects copy-id :shapes]))
            move     {:type :mov-objects :page-id page-id :parent-id uuid/zero :shapes [child-id]}
            messages (atom [])]
        (db/tx-run! th/*system*
                    (fn [cfg]
                      (fdata/with-tracked-pointers cfg file-id
                        (bfc/update-file! cfg broken))))
        (t/is (some? child-id))
        (t/is (= copy-id (get-in (cpc/process-changes (:data broken) [move])
                                 [:pages-index page-id :objects child-id :parent-id])))
        (let [n      (:revn broken)
              orders (atom 0)
              repair cfr/repair-file-until-valid
              out    (with-redefs [cfr/repair-file-until-valid (fn [& args]
                                                                 (swap! orders inc)
                                                                 (apply repair args))
                                   mbus/pub! (fn [_ & {:keys [message]}]
                                               (swap! messages conj message))]
                       (update-file-v2 ctx n [(commit [move] [])]))
              result (:result out)
              stored (stored-file file-id)]
          (t/is (th/success? out))
          (t/is (= 2 @orders) "both orders are repairable, but the move takes effect only in the control")
          (t/is (= (+ n 2) (:saved-revn result)))
          (t/is (not (contains? result :repair)))
          (t/is (= [[(:id (first (:lagged result)))] [(:id (second (:lagged result)))]]
                   (mapv :revision-ids @messages)))
          (t/is (= [true nil] (mapv :repaired @messages)))
          (t/is (= files-update/repair-session-id (:session-id (first @messages))))
          (t/is (= uuid/zero (get-in stored [:data :pages-index page-id :objects child-id :parent-id])))
          (t/is (nil? (cfv/validate-file stored {}))))))))

(t/deftest opt-in-replay-and-snapshot-do-not-load-unaffected-pages
  (with-redefs [cf/flags (-> cf/flags (conj :file-validation) (disj :auto-file-snapshot))]
    (let [{:keys [profile file-id page-id shape-b revn] :as ctx} (setup-file)
          unaffected (uuid/next)
          _          (t/is (th/success? (update-file (:id profile) file-id revn
                                                     [{:type :add-page :id unaffected :name "untouched"}])))
          broken     (break-frame (update ctx :revn inc))
          pointer    (-> (bfc/get-file th/*system* file-id) :data :pages-index (get unaffected))
          loads      (atom [])
          load       fdata/load-pointer
          out        (with-redefs [fdata/load-pointer (fn [cfg file-id fragment-id]
                                                        (swap! loads conj fragment-id)
                                                        (load cfg file-id fragment-id))]
                       (update-file-v2 ctx (:revn broken) [(commit [(set-attr page-id shape-b :name "edited")] [])]))]
      (t/is (pmap/pointer-map? pointer))
      (t/is (some? (get-in out [:result :repair])))
      (t/is (not-any? #{(pmap/get-id pointer)} @loads)))))

(defn- make-session-token
  [profile]
  (let [cfg th/*system*]
    (->> (session/create-session (::session/manager cfg) {:profile-id (:id profile)})
         (#'session/assign-token cfg)
         :token)))

(defn- with-http-router
  [f]
  ;; DummyRequest has no Undertow query parser. Use a loopback listener
  ;; so both the real GET query and the real Transit POST are decoded.
  (let [handler (#'http/router-handler (::http/router th/*system*))
        ^Undertow server (yt/start! (yt/server handler {:http/host "127.0.0.1" :http/port 0 :xnio/io-threads 1}))]
    (try
      (let [^Undertow$ListenerInfo listener (first (.getListenerInfo server))
            ^InetSocketAddress addr (.getAddress listener)]
        (f (str "http://127.0.0.1:" (.getPort addr))))
      (finally
        (yt/stop! server)))))

(defn- http-file-request
  [origin token file-id command params]
  (let [uri      (URI/create (str origin "/api/main/methods/" (name command) "?id=" file-id))
        request  (doto (HttpRequest/newBuilder uri)
                   (.timeout (Duration/ofSeconds 10))
                   (.header "Accept" "application/transit+json")
                   (.header "Cookie" (str "auth-token=" token)))
        request  (cond-> request
                   params (.header "Content-Type" "application/transit+json")
                   params (.POST (HttpRequest$BodyPublishers/ofString (transit/encode-str (assoc params :id file-id)))))]
    (with-open [client (HttpClient/newHttpClient)]
      (let [response (.send client (.build request) (HttpResponse$BodyHandlers/ofString))]
        (t/is (= "application/transit+json" (-> response .headers (.firstValue "content-type") (.orElse nil))))
        [(.statusCode response) (transit/decode-str (.body response))]))))

(defn- get-repair-snapshot-data
  [file-id revn]
  (let [row  (first (filter #(= (str "internal/repair/" revn) (:label %)) (label-rows file-id)))
        snap (fsnap/get-snapshot th/*system* file-id (:id row))]
    (:data (fdata/realize th/*system* (assoc snap :id file-id)))))

(t/deftest transit-opt-in-fallback-replays-relinked-media-without-touching-components
  (thi/reset-idmap!)
  (with-full-repair-flags
    (partial
     with-http-router
     (fn [origin]
       (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
             source    (th/create-file* 2 {:profile-id (:id profile)
                                           :project-id (:default-project-id profile)})
             image     (create-image (:id source))
             fills     (image-fills image)
             component (-> (with-meta (stored-file file-id) {:current-page-id page-id})
                           (tho/add-simple-component-with-copy :component :main :main-child :copy
                                                               :main-child-params {:fills fills}))
             main      (thi/id :main-child)
             copy      (first (get-in component [:data :pages-index page-id :objects (thi/id :copy) :shapes]))
             get-shape (fn [file id] (get-in file [:data :pages-index page-id :objects id]))
             fill-id   (fn [file id] (get-in (get-shape file id) [:fills 0 :fill-image :id]))
             _         (db/tx-run! th/*system*
                                   (fn [cfg]
                                     (fdata/with-tracked-pointers cfg file-id
                                       (bfc/update-file! cfg component))))
             broken    (break-frame ctx)
             n         (:revn broken)
             redo      [(set-attr page-id shape-b :fills fills)
                        (set-attr page-id copy :name "renamed copy")
                        (set-attr page-id main :name (:name (get-shape broken main)))]
             undo      [(set-attr page-id copy :name (:name (get-shape broken copy)))
                        (set-attr page-id shape-b :fills (:fills (get-shape broken shape-b)))]
             token     (make-session-token profile)
             session   (uuid/next)
             params    {:session-id session :revn n :vern 0 :features cfeat/supported-features
                        :repair-in-response true :commits [(commit redo undo)]}
             [status result] (http-file-request origin token file-id :update-file-v2 params)
             [get-status recovered] (http-file-request origin token file-id :get-file nil)
             rows      (filterv #(> (:revn %) n) (change-rows file-id))
             payloads  (mapv (fn [{:keys [id]}]
                               (blob/decode (:data (th/db-get :file-data
                                                              {:file-id file-id :id id :type "revision"}
                                                              {::db/remove-deleted false}))))
                             rows)
             snapshot  (get-repair-snapshot-data file-id n)
             refs      (th/db-query :file-media-object {:file-id file-id})
             linked-id (:id (first refs))]
         (t/is (= 200 status get-status))
         (t/is (= {:revn n :saved-revn (+ n 2) :media-relinked true}
                  (dissoc result :lagged)))
         (t/is (= [(inc n) (+ n 2)] (mapv :revn rows)))
         (t/is (= [files-update/repair-session-id session] (mapv :session-id rows)))
         (t/is (= (mapv :id rows) (mapv :id (:lagged result))))
         (t/is (= (mapv #(blob/decode (:changes %)) rows)
                  (mapv :changes (:lagged result))
                  (mapv :redo-changes payloads)))
         (t/is (= [[] undo] (mapv :undo-changes payloads)))
         (t/is (= (:data broken) snapshot))
         (t/is (= (:data recovered) (reduce cpc/process-changes snapshot (map :redo-changes payloads))))
         (t/is (= 1 (count refs)))
         (t/is (not= (:id image) linked-id))
         (t/is (= (:id image) (fill-id broken copy) (fill-id broken main)))
         (t/is (= linked-id (fill-id recovered shape-b) (fill-id recovered copy) (fill-id recovered main)))
         (t/is (= #{:name-group} (:touched (get-shape recovered copy))))
         (t/is (= (get-in broken [:data :components]) (get-in recovered [:data :components]))))))))

(t/deftest transit-opt-in-fallback-omits-media-marker-for-a-local-reference
  (with-full-repair-flags
    (partial
     with-http-router
     (fn [origin]
       (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
             image  (create-image file-id)
             broken (break-frame ctx)
             n      (:revn broken)
             redo   [(set-attr page-id shape-b :fills (image-fills image))]
             [status result] (http-file-request origin (make-session-token profile) file-id :update-file-v2
                                                {:session-id (uuid/next) :revn n :vern 0
                                                 :features cfeat/supported-features :repair-in-response true
                                                 :commits [(commit redo [])]})]
         (t/is (= 200 status))
         (t/is (= {:revn n :saved-revn (+ n 2)} (dissoc result :lagged)))
         (t/is (= redo (:changes (peek (:lagged result)))))
         (t/is (= [(:id image)] (mapv :id (th/db-query :file-media-object {:file-id file-id})))))))))

(t/deftest transit-audit-timeout-returns-504-after-saving-and-publishing
  (with-repair-flags
    (partial
     with-http-router
     (fn [origin]
       (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file :is-shared true)
             broken  (break-frame ctx)
             n       (:revn broken)
             token   (make-session-token profile)
             redo    [(set-attr page-id shape-b :name "saved before timeout")]
             events  (atom [])
             cfg     th/*system*
             publish mbus/pub!
             params  {:session-id (uuid/next) :revn n :vern 0 :features cfeat/supported-features
                      :repair-in-response true :commits [(commit redo [])]}
             [status result]
             (with-redefs [audit/submit (fn [_ event]
                                          (swap! events conj [:audit (get-in event [:props :revn])])
                                          (throw (PSQLException. "audit query cancelled" PSQLState/QUERY_CANCELED)))
                           mbus/pub! (fn [bus & {:keys [message] :as args}]
                                       (apply publish bus (mapcat identity args))
                                       (when (= file-id (:file-id message))
                                         (swap! events conj [(:type message) (:revn message)
                                                             (:revn (bfc/get-file cfg file-id))])))]
               (http-file-request origin token file-id :update-file-v2 params))
             [get-status recovered] (http-file-request origin token file-id :get-file nil)]
         (t/is (= 504 status))
         (t/is (= {:type :server-error :code :statement-timeout}
                  (select-keys result [:type :code])))
         (t/is (= 200 get-status))
         (t/is (= (+ n 2) (:revn recovered)))
         (t/is (= "saved before timeout" (get-in recovered [:data :pages-index page-id :objects shape-b :name])))
         (t/is (= [(inc n) (+ n 2)] (mapv :revn (filter #(> (:revn %) n) (change-rows file-id)))))
         (t/is (= [[:file-change (+ n 2) (+ n 2)]
                   [:library-change (+ n 2) (+ n 2)]
                   [:audit (+ n 2)]]
                  @events)))))))

(t/deftest transit-opt-in-repair-replays-a-multi-pass-structural-save
  (with-full-repair-flags
    (partial
     with-http-router
     (fn [origin]
       (let [{:keys [profile file-id page-id shape-b revn]} (setup-file)
             frame-id (uuid/next)
             damaged  (uuid/next)
             board    (fn [id]
                        (assoc (add-rect page-id id)
                               :obj (cts/setup-shape {:id id :name "board" :type :frame
                                                      :frame-id uuid/zero :parent-id uuid/zero})))
             missing  [(uuid/next) (uuid/next) (uuid/next)]
             _        (t/is (th/success?
                             (update-file (:id profile) file-id revn
                                          [(board frame-id) (board damaged)
                                           (set-attr page-id damaged :shapes missing)]
                                          :skip-validate true)))
             broken   (stored-file file-id)
             n        (:revn broken)
             move     {:type :mov-objects :page-id page-id :parent-id frame-id :shapes [shape-b]}
             redo     [move]
             undo     [(assoc move :parent-id uuid/zero)]
             token    (make-session-token profile)
             params   {:session-id (uuid/next) :revn n :vern 0
                       :features cfeat/supported-features :repair-in-response true
                       :commits [(commit redo undo)]}
             [status result] (http-file-request origin token file-id :update-file-v2 params)
             [get-status recovered] (http-file-request origin token file-id :get-file nil)
             rows     (->> (change-rows file-id) (filter #(> (:revn %) n)) (sort-by :revn))
             payloads (mapv (fn [{:keys [id]}]
                              (blob/decode (:data (th/db-get :file-data
                                                             {:file-id file-id :id id :type "revision"}
                                                             {::db/remove-deleted false}))))
                            rows)
             snap     (get-repair-snapshot-data file-id n)]
         (t/is (= 200 status get-status))
         (t/is (= (inc n) (:revn (first rows))))
         (t/is (= (+ n 2) (:saved-revn result) (:revn recovered)))
         (t/is (= (mapv :id rows) (mapv :id (:lagged result))))
         (t/is (= {:id (:id (second rows)) :revn (+ n 2)
                   :changes (:redo-changes (second payloads))}
                  (:repair result)))
         (t/is (= [{:redo-changes redo :undo-changes undo}
                   {:redo-changes (get-in result [:repair :changes]) :undo-changes []}]
                  payloads))
         (t/is (> (count (get-in result [:repair :changes])) (count missing)))
         (t/is (= (:data broken) snap))
         (t/is (= frame-id (get-in recovered [:data :pages-index page-id :objects shape-b :parent-id])))
         (t/is (= (:data recovered)
                  (reduce cpc/process-changes snap (map :redo-changes payloads))
                  (cpc/process-changes snap (into redo (get-in result [:repair :changes]))))))))))

(t/deftest transit-opt-in-reports-only-the-accepted-prefix
  (with-full-repair-flags
    (partial
     with-http-router
     (fn [origin]
       (let [{:keys [profile file-id page-id shape-a shape-b revn]} (setup-file)
             token    (make-session-token profile)
             accepted (commit [(set-attr page-id shape-a :name "accepted")] [(set-attr page-id shape-a :name "rect")])
             rejected (commit [(set-attr page-id shape-b :frame-id (uuid/next))] [])
             [status result] (http-file-request origin token file-id :update-file-v2
                                                {:session-id (uuid/next) :revn revn :vern 0
                                                 :features cfeat/supported-features :repair-in-response true
                                                 :commits [accepted rejected]})
             [get-status recovered] (http-file-request origin token file-id :get-file nil)
             row     (last (change-rows file-id))
             payload (th/db-get :file-data {:file-id file-id :id (:id row) :type "revision"}
                                {::db/remove-deleted false})]
         (t/is (= 200 status get-status))
         (t/is (= (inc revn) (:saved-revn result) (:revn recovered)))
         (t/is (not (contains? result :repair)))
         (t/is (not (contains? result :media-relinked)))
         (t/is (= [(:commit-id rejected)] (get-in result [:rejected :commit-ids])))
         (t/is (= (select-keys accepted [:redo-changes :undo-changes]) (blob/decode (:data payload))))
         (t/is (= "accepted" (get-in recovered [:data :pages-index page-id :objects shape-a :name])))
         (t/is (= uuid/zero (get-in recovered [:data :pages-index page-id :objects shape-b :frame-id]))))))))

(t/deftest transit-v1-and-non-opt-in-v2-retain-the-old-contract
  (with-repair-flags
    (partial
     with-http-router
     (fn [origin]
       (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
             token  (make-session-token profile)
             source (th/create-file* 2 {:profile-id (:id profile)
                                        :project-id (:default-project-id profile)})
             image  (create-image (:id source))]
         (doseq [command [:update-file :update-file-v2]]
           (let [broken (break-frame (assoc ctx :revn (:revn (stored-file file-id))))
                 n      (:revn broken)
                 redo   [(set-attr page-id shape-b :fills (image-fills image))]
                 ;; v1 accepts undeclared keys but never activates the v2 path.
                 params (merge {:session-id (uuid/next) :revn n :vern 0
                                :features cfeat/supported-features}
                               (if (= :update-file command)
                                 {:changes redo :repair-in-response true}
                                 {:commits [(commit redo [])]}))
                 [status result] (http-file-request origin token file-id command params)]
             (t/is (= 200 status))
             (t/is (= #{:revn :lagged} (set (keys result))))
             (t/is (= [(inc n) (+ n 2)] (mapv :revn (:lagged result))))
             (t/is (= redo (:changes (peek (:lagged result)))))
             (t/is (not= (:id image) (get-in (stored-file file-id)
                                             [:data :pages-index page-id :objects shape-b :fills 0 :fill-image :id])))
             (t/is (= [files-update/repair-session-id (:session-id params)]
                      (mapv :session-id (:lagged result)))))))))))

(t/deftest repairs-preexisting-frame-not-found
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a shape-b] :as ctx} (setup-file)
            broken (break-frame ctx)
            before (repair-count :repaired)
            out    (update-file (:id profile) file-id (:revn broken)
                                [(set-attr page-id shape-b :name "renamed")])
            stored (stored-file file-id)
            shape  (get-in stored [:data :pages-index page-id :objects shape-a])
            frame  (get-in stored [:data :pages-index page-id :objects (:frame-id shape)])]
        (t/is (th/success? out))
        (t/is (nil? (cfv/validate-file stored {})))
        (t/is (some? frame))
        (t/is (= "renamed"
                 (get-in stored [:data :pages-index page-id :objects shape-b :name])))
        (t/is (= 1.0 (- (repair-count :repaired) before)))))))

(t/deftest every-revision-of-a-repair-carries-its-payload
  ;; A repair writes two revisions: the one that fixes the preexisting damage
  ;; and the one of the client's save. Both must have their file_data payload,
  ;; so a reader that prefers the payload never finds a hole in the history.
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
            broken       (break-frame ctx)
            redo-changes [(set-attr page-id shape-b :name "renamed")]
            undo-changes [(set-attr page-id shape-b :name "rect")]
            out          (th/command! {::th/type :update-file-v2
                                       ::rpc/profile-id (:id profile)
                                       :id file-id
                                       :session-id (uuid/next)
                                       :revn (:revn broken)
                                       :vern 0
                                       :features cfeat/supported-features
                                       :commits [{:commit-id (uuid/next)
                                                  :redo-changes redo-changes
                                                  :undo-changes undo-changes}]})
            rows         (filterv #(> (:revn %) (:revn broken))
                                  (change-rows file-id))]
        (t/is (th/success? out))
        (t/is (= 2 (count rows)))
        (doseq [row rows
                :let [repair? (= files-update/repair-session-id (:session-id row))]]
          (let [payload (th/db-get :file-data
                                   {:file-id file-id
                                    :id (:id row)
                                    :type "revision"}
                                   {::db/remove-deleted false})]
            (t/is (some? payload) (str "payload missing for revn " (:revn row)))
            (t/is (= (:deleted-at row) (:deleted-at payload)))
            (t/is (= {:redo-changes (blob/decode (:changes row))
                      :undo-changes (if repair? [] undo-changes)}
                     (blob/decode (:data payload))))))))))

(t/deftest repairs-preexisting-frame-not-found-with-schema-validation
  (with-full-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a shape-b] :as ctx} (setup-file)
            broken           (break-frame ctx)
            n                (:revn broken)
            before-repaired  (repair-count :repaired)
            before-unrepaired (repair-count :unrepaired)
            out              (update-file (:id profile) file-id n
                                          [(set-attr page-id shape-b :name "renamed")])
            stored           (stored-file file-id)
            shape            (get-in stored [:data :pages-index page-id :objects shape-a])
            frame            (get-in stored [:data :pages-index page-id :objects (:frame-id shape)])
            labels           (label-rows file-id)
            repair           (filter #(= (str "internal/repair/" n) (:label %)) labels)
            snap             (filter #(= (str "internal/snapshot/" n) (:label %)) labels)]
        (t/is (th/success? out))
        (t/is (nil? (cfv/validate-file stored {})))
        (t/is (some? frame))
        (t/is (= "renamed"
                 (get-in stored [:data :pages-index page-id :objects shape-b :name])))
        (t/is (= (+ n 2) (:revn stored)))
        (t/is (= 1 (count repair)))
        (t/is (zero? (count snap)))
        (t/is (= 1.0 (- (repair-count :repaired) before-repaired)))
        (t/is (= 0.0 (- (repair-count :unrepaired) before-unrepaired)))))))

(t/deftest rejects-client-caused-break
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a revn]} (setup-file)
            before (stored-file file-id)
            out    (update-file (:id profile) file-id revn
                                [(set-attr page-id shape-a :frame-id (uuid/next))])
            after  (stored-file file-id)]
        (t/is (not (th/success? out)))
        (t/is (= :referential-integrity (:code (ex-data (:error out)))))
        (t/is (= (:revn before) (:revn after)))))))

(t/deftest skip-validate-does-not-repair
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a shape-b] :as ctx} (setup-file)
            broken (break-frame ctx)
            out    (update-file (:id profile) file-id (:revn broken)
                                [(set-attr page-id shape-b :name "renamed")]
                                :skip-validate true)
            stored (stored-file file-id)
            shape  (get-in stored [:data :pages-index page-id :objects shape-a])]
        (t/is (th/success? out))
        (t/is (nil? (get-in stored [:data :pages-index page-id :objects (:frame-id shape)])))))))

(t/deftest damage-on-untouched-page-is-left
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a revn]} (setup-file)
            page-2 (uuid/next)
            shape-c (uuid/next)
            _      (t/is (th/success?
                          (update-file (:id profile) file-id revn
                                       [{:type :add-page :id page-2 :name "page-2"}
                                        (add-rect page-2 shape-c)])))
            file   (stored-file file-id)
            _      (t/is (th/success?
                          (update-file (:id profile) file-id (:revn file)
                                       [(set-attr page-2 shape-c :frame-id (uuid/next))]
                                       :skip-validate true)))
            file   (stored-file file-id)
            out    (update-file (:id profile) file-id (:revn file)
                                [(set-attr page-id shape-a :name "on-page-1")])
            stored (stored-file file-id)
            shape  (get-in stored [:data :pages-index page-2 :objects shape-c])]
        (t/is (th/success? out))
        (t/is (nil? (get-in stored [:data :pages-index page-2 :objects (:frame-id shape)])))))))

(t/deftest growing-scope-repairs-component-touched-by-repair
  (thi/reset-idmap!)
  (with-repair-flags
    (fn []
      (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                        (assoc :revn 0)
                        (tho/add-simple-component :component1 :root1 :child1)
                        (ths/add-sample-shape :other)
                        (update-in [:data :components (thi/id :component1)]
                                   assoc :main-instance-id (uuid/next)
                                   :objects {(uuid/next) {:id (uuid/next)}}))
            page-id (thi/id :page1)
            changes [(set-attr page-id (thi/id :other) :name "renamed")]
            cfg     {::files-update/validation-timings (atom {})}
            result  (binding [pmap/*tracked* (pmap/create-tracked)]
                      (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})]
                        (@#'files-update/process-changes-and-validate cfg file changes nil nil)))
            stored  result]
        (t/is (some? (::files-update/repair stored)))
        (t/is (nil? (cfv/validate-file stored {})))
        (t/is (nil? (get-in stored [:data :components (thi/id :component1) :objects])))))))

(t/deftest repair-on-copy-page-keeps-copy-of-lost-main
  (thi/reset-idmap!)
  (with-repair-flags
    (fn []
      (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                        (assoc :revn 0)
                        (tho/add-simple-component :component1 :root1 :child1)
                        (thf/add-sample-page :page2)
                        (thc/instantiate-component :component1 :copy1)
                        (thc/update-component :component1 :main-instance-id (uuid/next)))
            page-id (thi/id :page2)
            changes [(set-attr page-id (thi/id :copy1) :name "renamed")]
            cfg     {::files-update/validation-timings (atom {})}
            stored  (binding [pmap/*tracked* (pmap/create-tracked)]
                      (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})]
                        (@#'files-update/process-changes-and-validate cfg file changes nil nil)))
            copy1   (get-in stored [:data :pages-index page-id :objects (thi/id :copy1)])]
        (t/is (some? (::files-update/repair stored)))
        (t/is (nil? (cfv/validate-file stored {})))
        (t/is (= (thi/id :root1)
                 (get-in stored [:data :components (thi/id :component1) :main-instance-id])))
        (t/is (= "renamed" (:name copy1)))
        (t/is (= (thi/id :root1) (:shape-ref copy1)))
        (t/is (= (thi/id :component1) (:component-id copy1)))))))

(t/deftest repair-records-timings-with-schema-validation
  (thi/reset-idmap!)
  (with-full-repair-flags
    (fn []
      (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                        (assoc :revn 0)
                        (ths/add-sample-shape :shape1)
                        (ths/update-shape :shape1 :frame-id (uuid/next)))
            changes [(set-attr (thi/id :page1) (thi/id :shape1) :name "renamed")]
            timings (atom {})
            cfg     {::files-update/validation-timings timings}
            result  (binding [pmap/*tracked* (pmap/create-tracked)]
                      (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})]
                        (@#'files-update/process-changes-and-validate cfg file changes nil nil)))]
        (t/is (some? (::files-update/repair result)))
        (t/is (number? (:repair-ms @timings)))
        (t/is (number? (:reprocess-changes-ms @timings)))
        (t/is (number? (:validate-schema-ms @timings)))))))

(t/deftest unrepairable-rejects-and-calls-repair-once
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
            broken (break-frame ctx)
            calls  (atom 0)
            before (repair-count :unrepaired)
            out    (with-redefs [cfr/repair-error (fn [_ _ _ _]
                                                    (swap! calls inc)
                                                    {:redo-changes []})]
                     (update-file (:id profile) file-id (:revn broken)
                                  [(set-attr page-id shape-b :name "renamed")]))]
        (t/is (not (th/success? out)))
        (t/is (= :referential-integrity (:code (ex-data (:error out)))))
        ;; `calls` counts repairs, one per error; `break-frame` yields a
        ;; single error, so one call means one pass.
        (t/is (= 1 @calls))
        (t/is (= 1.0 (- (repair-count :unrepaired) before)))))))

(t/deftest repair-writes-change-rows-and-reuses-snapshot
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
            broken  (break-frame ctx)
            before  (count (change-rows file-id))
            session (uuid/next)
            calls   (atom [])
            n       (:revn broken)
            out     (with-redefs [mbus/pub! (fn [_cfg & {:keys [topic message]}]
                                              (swap! calls conj {:topic topic :message message}))]
                      (update-file (:id profile) file-id n
                                   [(set-attr page-id shape-b :name "renamed")]
                                   :session-id session))
            rows    (change-rows file-id)
            new     (subvec rows before)
            labels  (label-rows file-id)
            repair  (filter #(= (str "internal/repair/" n) (:label %)) labels)
            snap    (filter #(= (str "internal/snapshot/" n) (:label %)) labels)
            msgs    (filter #(= :file-change (:type (:message %))) @calls)
            stored  (stored-file file-id)]
        (t/is (th/success? out))
        (t/is (= "renamed"
                 (get-in stored [:data :pages-index page-id :objects shape-b :name])))
        (t/is (= 2 (count new)))
        (t/is (= (inc n) (:revn (first new))))
        (t/is (= files-update/repair-session-id (:session-id (first new))))
        ;; the repair is a revision like the save: no features, a changes blob
        (t/is (= 2 (:kind (first new))))
        (t/is (nil? (:features (first new))))
        (t/is (some? (:changes (first new))))
        (t/is (some? (:deleted-at (first new))))
        (t/is (= (:created-at (first new)) (:updated-at (first new))))
        (t/is (= (+ n 2) (:revn (second new))))
        (t/is (= session (:session-id (second new))))
        (t/is (some? (:deleted-at (second new))))
        (t/is (= (:created-at (second new)) (:updated-at (second new))))
        (t/is (not (pos? (compare (:created-at (first new))
                                  (:created-at (second new))))))
        (t/is (= 1 (count repair)))
        (t/is (zero? (count snap)))
        (t/is (>= (count msgs) 2))
        (t/is (true? (get-in (first msgs) [:message :repaired])))
        (t/is (= files-update/repair-session-id
                 (get-in (first msgs) [:message :session-id])))
        (t/is (= session (get-in (second msgs) [:message :session-id])))
        (t/is (not (true? (get-in (second msgs) [:message :repaired]))))
        (t/is (= (+ n 2) (get-in (second msgs) [:message :revn])))))))

(t/deftest repair-library-change-keeps-saver-session
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file :is-shared true)
            broken  (break-frame ctx)
            session (uuid/next)
            calls   (atom [])
            out     (with-redefs [mbus/pub! (fn [_cfg & {:keys [topic message]}]
                                              (swap! calls conj {:topic topic :message message}))]
                      (update-file (:id profile) file-id (:revn broken)
                                   [(set-attr page-id shape-b :name "renamed")]
                                   :session-id session))
            lib-msgs (filter #(= :library-change (:type (:message %))) @calls)]
        (t/is (th/success? out))
        (t/is (seq lib-msgs))
        (doseq [msg lib-msgs]
          (t/is (= session (get-in msg [:message :session-id])))
          (t/is (not= files-update/repair-session-id
                      (get-in msg [:message :session-id]))))))))

(t/deftest repair-snapshot-without-auto-snapshot
  (with-redefs [cf/flags (-> cf/flags
                             (conj :file-validation)
                             (disj :auto-file-snapshot))]
    (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
          broken (break-frame ctx)
          n      (:revn broken)
          out    (update-file (:id profile) file-id n
                              [(set-attr page-id shape-b :name "renamed")])
          labels (label-rows file-id)
          repair (filter #(= (str "internal/repair/" n) (:label %)) labels)
          snaps  (filter #(re-find #"^internal/snapshot/" (str (:label %))) labels)
          row    (first repair)
          snap   (fsnap/get-snapshot th/*system* file-id (:id row))
          sfile  {:id file-id
                  :features (:features (stored-file file-id))
                  :data (:data snap)}]
      (t/is (th/success? out))
      (t/is (= 1 (count repair)))
      (t/is (zero? (count snaps)))
      (t/is (false? (pointer-map-in? (:data snap))))
      (t/is (some? (cfv/validate-file sfile {}))))))

(t/deftest srepl-repair-grows-revn-by-iterations
  (thi/reset-idmap!)
  (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                    (assoc :revn 3)
                    (ths/add-sample-shape :shape1)
                    (ths/update-shape :shape1 :frame-id (uuid/next)))
        loop    (cfr/repair-file-until-valid
                 file {}
                 (fn [file _] (cfv/validate-file file {})))
        wrapped (file-repair/repair-file file {})]
    (t/is (pos? (:iterations loop)))
    (t/is (= (+ (:revn file) (:iterations loop)) (:revn wrapped)))
    (t/is (nil? (cfv/validate-file wrapped {})))))

(t/deftest healthy-save-writes-one-change-row
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-b revn]} (setup-file)
            before (count (change-rows file-id))
            out    (update-file (:id profile) file-id revn
                                [(set-attr page-id shape-b :name "ok")])
            labels (label-rows file-id)]
        (t/is (th/success? out))
        (t/is (= 1 (- (count (change-rows file-id)) before)))
        (t/is (empty? (filter #(re-find #"^internal/repair/" (str (:label %))) labels)))))))

(t/deftest repairs-several-missing-children-on-root
  (with-full-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a shape-b revn]} (setup-file)
            r1     (uuid/next)
            r2     (uuid/next)
            r3     (uuid/next)
            _      (t/is (th/success?
                          (update-file (:id profile) file-id revn
                                       [(set-attr page-id uuid/zero :shapes
                                                  [shape-a shape-b r1 r2 r3])]
                                       :skip-validate true)))
            broken (stored-file file-id)
            errors (cfv/validate-file broken {})
            out    (update-file (:id profile) file-id (:revn broken)
                                [(set-attr page-id shape-b :name "renamed")])
            stored (stored-file file-id)
            root   (get-in stored [:data :pages-index page-id :objects uuid/zero])]
        (t/is (= [:child-not-found :child-not-found :child-not-found]
                 (mapv :code errors)))
        (t/is (= #{uuid/zero} (into #{} (map :shape-id) errors)))
        (t/is (th/success? out))
        (t/is (= [shape-a shape-b] (:shapes root)))
        (t/is (nil? (cfv/validate-file stored {})))
        (t/is (= "renamed"
                 (get-in stored [:data :pages-index page-id :objects shape-b :name])))))))
