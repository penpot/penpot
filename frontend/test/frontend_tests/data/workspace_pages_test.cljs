;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-pages-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.thumbnails :as thc]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.persistence :as dps]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.pages :as dwpg]
   [app.main.repo :as rp]
   [app.main.worker :as mw]
   [app.render-wasm.api :as wasm.api]
   [app.util.queue :as q]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

(defn- pages
  [state]
  (let [file-id (:current-file-id state)]
    (get-in state [:files file-id :data :pages])))

(defn- make-five-pages
  []
  (-> (cthf/sample-file :file1 :page-label :page1)
      (cthf/add-sample-page :page2)
      (cthf/add-sample-page :page3)
      (cthf/add-sample-page :page4)
      (cthf/add-sample-page :page5)
      (cthf/switch-to-page :page1)))

;; ---------------------------------------------------------------------------
;; Verify the order of undo changes so that when pages are restored they
;; appear in the original page order (not reversed).
;; ---------------------------------------------------------------------------

(defn- add-page-ids-from-undo-changes
  "Extract the sequence of page ids from the `:add-page` undo changes list."
  [changes]
  (->> (:undo-changes changes)
       (keep (fn [change]
               (when (= :add-page (:type change))
                 (:id change))))))

(t/deftest delete-pages-undo-changes-are-in-page-order
  (let [file      (make-five-pages)
        fdata     (:data file)
        pindex    (:pages-index fdata)
        pages     (:pages fdata)
        page2-id  (cthi/id :page2)
        page4-id  (cthi/id :page4)
        del-ids   (filter #{page2-id page4-id} pages)

        ;; Simulate the reduction from delete-pages but in REVERSE order
        ;; (the fix iterates (reverse del-ids) so undo changes are in correct order)
        changes   (reduce
                   (fn [changes id]
                     (let [page (-> (get pindex id)
                                    (assoc :index (d/index-of pages id)))]
                       (pcb/del-page changes page)))
                   (pcb/empty-changes)
                   del-ids)

        undo-before-fix (add-page-ids-from-undo-changes changes)

        changes-fixed   (reduce
                         (fn [changes id]
                           (let [page (-> (get pindex id)
                                          (assoc :index (d/index-of pages id)))]
                             (pcb/del-page changes page)))
                         (pcb/empty-changes)
                         (reverse del-ids))

        undo-after-fix (add-page-ids-from-undo-changes changes-fixed)]

    (t/is (= [page4-id page2-id] undo-before-fix)
          "before fix: undo changes are reversed (page order)")
    (t/is (= [page2-id page4-id] undo-after-fix)
          "after fix: undo changes are in correct page order")))

;; ---------------------------------------------------------------------------
;; delete-pages removes the correct pages and keeps remaining pages in order
;; ---------------------------------------------------------------------------

(t/deftest delete-pages-removes-pages-and-keeps-remaining-in-order
  (t/async
    done
    (let [file      (make-five-pages)
          page2-id  (cthi/id :page2)
          page4-id  (cthi/id :page4)
          store     (ths/setup-store file)
          events    [(dwpg/delete-pages [page2-id page4-id])]]

      (ths/run-store
       store done events
       (fn [state]
         (let [pgs (pages state)]
           (t/is (= 3 (count pgs))
                 "3 pages remain after deleting 2")
           (t/is (= [(cthi/id :page1) (cthi/id :page3) (cthi/id :page5)]
                    pgs)
                 "remaining pages stay in the original order")))))))

;; ---------------------------------------------------------------------------
;; delete-pages keeps at least one page in the file
;; ---------------------------------------------------------------------------

(t/deftest delete-pages-keeps-at-least-one-page
  (t/async
    done
    (let [file      (make-five-pages)
          page1-id  (cthi/id :page1)
          page2-id  (cthi/id :page2)
          page3-id  (cthi/id :page3)
          page4-id  (cthi/id :page4)
          page5-id  (cthi/id :page5)
          store     (ths/setup-store file)
          events    [(dwpg/delete-pages [page1-id page2-id page3-id page4-id page5-id])]]

      (ths/run-store
       store done events
       (fn [state]
         (let [pgs (pages state)]
           (t/is (= 1 (count pgs))
                 "at least one page remains")
           (t/is (= page1-id (first pgs))
                 "the first page (in page order) is kept")))))))

;; ---------------------------------------------------------------------------
;; Adding a page from the sitemap opens it and starts renaming it, once
;; the new page has initialized.
;; ---------------------------------------------------------------------------

(t/deftest ^:async add-page-opens-and-renames-the-new-page
  (let [file-id (uuid/next)
        stream  (rx/subject)
        event   (dwpg/create-page-and-focus {:file-id file-id})
        events  (atom [])
        done    (async/observe (ptk/watch event {} stream)
                               :on-next #(swap! events conj %))]

    (await (async/settle))
    (let [[create interrupt go-to] @events
          page-id                   (:id (ev/-data create))]
      (t/is (= ::dwpg/create-page (ptk/type create)))
      (t/is (= :interrupt interrupt))
      (t/is (= ::dcm/go-to-workspace (ptk/type go-to)))

      ;; A page added by someone else initializes first: no rename.
      (rx/push! stream (ptk/data-event ::dwpg/initialized (uuid/next)))
      (rx/push! stream (ptk/data-event ::dwpg/initialized page-id))
      (await done)

      (let [rename (last @events)
            state  (ptk/update rename {:workspace-local {}})]
        (t/is (= 4 (count @events)))
        (t/is (= ::dwpg/start-rename-page-item (ptk/type rename)))
        (t/is (= page-id (get-in state [:workspace-local :page-item])))))))

;; ---------------------------------------------------------------------------
;; Renaming a page to its current name commits nothing, so the next undo
;; reverts the last visible change.
;; ---------------------------------------------------------------------------

(defn- ^:async rename-page-events
  [store page-id name]
  (let [events (atom [])]
    (await (async/observe (ptk/watch (dwpg/rename-page page-id name) @store (rx/subject))
                          :on-next #(swap! events conj %)))
    @events))

(t/deftest ^:async rename-page-to-the-same-name-commits-nothing
  (let [file    (cthf/sample-file :file1 :page-label :page1)
        page-id (cthi/id :page1)
        name    (:name (cthf/get-page file :page1))
        store   (ths/setup-store file)]
    (t/is (= [] (await (rename-page-events store page-id name))))
    (t/is (= [:app.main.data.changes/commit-changes]
             (map ptk/type (await (rename-page-events store page-id "Renamed")))))))

(t/deftest workspace-load-id-changes-on-every-open
  (let [team-id (uuid/next)
        file-id (uuid/next)
        opened  (ptk/update (dw/initialize-workspace team-id file-id) {})
        closed  (ptk/update (dw/finalize-workspace team-id file-id) opened)
        opened' (ptk/update (dw/initialize-workspace team-id file-id) closed)]
    (t/is (uuid? (:workspace-load-id opened)))
    (t/is (not (contains? closed :workspace-load-id)))
    (t/is (not= (:workspace-load-id opened) (:workspace-load-id opened')))))

(t/deftest ^:async reconciled-page-rebuilds-indices-without-reopening-the-workspace
  (let [file        (cthf/sample-file :file1 :page-label :page1)
        file-id     (:id file)
        page-id     (cthf/current-page-id file)
        repaired    (cths/add-sample-shape file :board :type :frame)
        board-id    (cthi/id :board)
        undo        {:index 0 :items [{:undo-group (uuid/next)}]}
        shell       {:selected (d/ordered-set board-id) :zoom 2 :vbox {:x 0 :y 0}}
        worker-page (atom nil)
        rendered    (atom nil)
        store       (ths/setup-store file)]
    (await
     (mock/with-mocks*
       {mw/ask! (mock/stub (fn [{:keys [cmd page]}]
                             (when (= :index/initialize cmd)
                               (reset! worker-page page))
                             (rx/empty)))
        wasm.api/initialized? (constantly true)
        wasm.api/initialize-viewport (fn [objects zoom vbox & _opts]
                                       (reset! rendered [objects zoom vbox]))}
       (ptk/emit! store #(-> %
                             (assoc :workspace-undo undo
                                    :workspace-local shell
                                    :features #{"render-wasm/v1"})
                             (assoc-in [:files file-id] repaired))
                  (ptk/event ::dw/reconciled-file {:file-id file-id :tokens-changed false}))
       (await (async/wait-for #(and (some? @worker-page) (some? @rendered))
                              "reconciled page sent to the worker and renderer"))
       (let [objects (get-in @store [:files file-id :data :pages-index page-id :objects])]
         (t/is (= [board-id] (mapv :id (::ctst/index-frames (meta objects)))))
         (t/is (= [objects (:zoom shell) (:vbox shell)] @rendered)))
       (t/is (= page-id (:id @worker-page)))
       (t/is (contains? (:objects @worker-page) board-id))
       (t/is (= undo (:workspace-undo @store)))
       (t/is (= shell (:workspace-local @store)))))))

(def ^:private original-thumbnail
  {:uri "original-thumbnail" :rendered-at nil})

(defn- get-thumbnail-id
  [file page-label shape-label tag]
  (thc/fmt-object-id (:id file) (cthi/id page-label) (cthi/id shape-label) tag))

(defn- setup-thumbnail-store
  [file renderer thumbnail-ids]
  (let [store   (ths/setup-store file {:renderer renderer})
        load-id (uuid/next)]
    (ptk/emit! store #(-> %
                          (dps/file-base-loaded file)
                          (assoc :workspace-load-id load-id
                                 :thumbnails (zipmap thumbnail-ids (repeat original-thumbnail)))))
    store))

(defn- ^:async with-thumbnail-reconciliation
  [store server-file verify]
  (let [file-id    (:id server-file)
        read$      (rx/subject)
        requested  (atom false)
        deleted    (atom [])
        uploaded   (atom [])
        batch-tick (rx/subject)
        timer      rx/timer]
    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub
                 (fn [command params]
                   (case command
                     :get-file
                     (if (= file-id (:id params))
                       (do (reset! requested true)
                           (rx/observe-on :async read$))
                       (rx/empty))

                     :delete-file-object-thumbnails
                     (do (swap! deleted conj (:object-ids params))
                         (rx/observe-on :async (rx/of nil)))

                     :create-file-object-thumbnail
                     (do (swap! uploaded conj params)
                         (rx/observe-on :async (rx/of nil)))

                     (rx/empty))))
        mw/ask! (mock/stub (fn [_] (rx/empty)))
        wasm.api/initialized? (constantly false)
        rx/timer (fn [ms & args]
                   (if (= ms 200)
                     batch-tick
                     (apply timer ms args)))}
       (ptk/emit! store (#'dps/reconcile-file file-id {:minimum (:revn server-file)}))
       (await (async/wait-for #(deref requested) "recovery GET is requested"))
       (rx/push! read$ server-file)
       (rx/end! read$)
       (await (async/wait-for-store
               store #(= (:revn server-file) (get-in @store [:files file-id :revn]))
               "recovery GET is installed"))
       ;; The thumbnail events run on the async scheduler. Let those events
       ;; reach the store before firing the deletion debounce.
       (await (async/settle))
       (rx/push! batch-tick :tick)
       (rx/end! batch-tick)
       (await (async/settle))
       (await (verify deleted uploaded))))))

(t/deftest ^:async recovery-keeps-existing-thumbnails-across-pages-in-wasm
  (let [file        (-> (cthf/sample-file :file :page-label :page1)
                        (ctho/add-frame :visible-board)
                        (ctho/add-minimal-component :component :main)
                        (cthf/add-sample-page :page2)
                        (ctho/add-frame :other-board)
                        (cthf/switch-to-page :page1))
        visible     (get-thumbnail-id file :page1 :visible-board "frame")
        component   (get-thumbnail-id file :page1 :main "component")
        other       (get-thumbnail-id file :page2 :other-board "frame")
        store       (setup-thumbnail-store file :wasm [visible component other])
        server-file (assoc file :revn 1)
        renders     (atom [])]
    (await
     (mock/with-mocks*
       {mw/emit! (mock/stub (fn [message]
                              (when (= :thumbnails/render-component (:cmd message))
                                (swap! renders conj message))
                              (rx/empty)))}
       (await
        (with-thumbnail-reconciliation
          store server-file
          (fn [deleted _]
            (t/is (empty? @deleted))
            (t/is (= {visible original-thumbnail
                      component original-thumbnail
                      other original-thumbnail}
                     (:thumbnails @store)))
            (t/is (= [[(:id file) (cthi/id :page1) (cthi/id :main)]]
                     (mapv (juxt :file-id :page-id :root-id) @renders)))
            (t/is (not-any? :persist? @renders)))))))))

(t/deftest ^:async recovery-deletes-only-the-thumbnail-of-the-server-removed-object
  (let [file          (-> (cthf/sample-file :file :page-label :page1)
                          (ctho/add-frame :removed-locally)
                          (cthf/add-sample-page :page2)
                          (ctho/add-frame :removed-on-server)
                          (cthf/switch-to-page :page1))
        page-id       (cthf/current-page-id file)
        pending       (-> (pcb/empty-changes nil page-id)
                          (pcb/with-objects (:objects (cthf/current-page file)))
                          (pcb/remove-objects [(cthi/id :removed-locally)])
                          (assoc :id (uuid/next) :file-id (:id file) :file-vern (:vern file)))
        local-thumb   (get-thumbnail-id file :page1 :removed-locally "frame")
        removed-thumb (get-thumbnail-id file :page2 :removed-on-server "frame")
        store         (setup-thumbnail-store (cthf/apply-changes file pending)
                                             :svg [local-thumb removed-thumb])
        server-file   (-> file
                          (ctho/delete-shape :removed-on-server :page-label :page2)
                          (assoc :revn 1))]
    (ptk/emit! store (#'dps/buffer-commit pending))
    (await
     (with-thumbnail-reconciliation
       store server-file
       (fn [deleted _]
         (let [installed (ths/get-file-from-state @store)]
           (t/is (some? (cths/get-shape server-file :removed-locally)))
           (t/is (nil? (cths/get-shape installed :removed-locally))
                 "The pending deletion is replayed on the recovery GET")
           (t/is (nil? (cths/get-shape installed :removed-on-server :page-label :page2))))
         (t/is (= [[removed-thumb]] @deleted))
         (t/is (nil? (get-in @store [:thumbnails removed-thumb]))))))))

(t/deftest ^:async recovery-replaces-the-visible-svg-thumbnail-without-deleting-it
  (let [file        (-> (cthf/sample-file :file :page-label :page1)
                        (ctho/add-frame :visible-board)
                        (cthf/add-sample-page :page2)
                        (ctho/add-frame :other-board)
                        (cthf/switch-to-page :page1))
        visible     (get-thumbnail-id file :page1 :visible-board "frame")
        other       (get-thumbnail-id file :page2 :other-board "frame")
        store       (setup-thumbnail-store file :svg [visible other])
        server-file (-> file
                        (cths/update-shape :visible-board :fills [{:fill-color "#00ff00"
                                                                   :fill-opacity 1}])
                        (assoc :revn 1))
        renders     (atom [])
        render$     (rx/subject)
        blob        (wapi/create-blob "replacement" "image/png")]
    (await
     (mock/with-mocks*
       {q/enqueue-unique (fn [_ request _]
                           (swap! renders conj (unchecked-get request "page-id"))
                           (rx/observe-on :async render$))
        wapi/create-uri (constantly "replacement-thumbnail")}
       (await
        (with-thumbnail-reconciliation
          store server-file
          (^:async fn [deleted uploaded]
            (t/is (= [(cthi/id :page1)] @renders))
            (t/is (empty? @deleted))
            (t/is (= original-thumbnail (get-in @store [:thumbnails visible]))
                  "The previous thumbnail remains until a replacement arrives")
            (rx/push! render$ blob)
            (rx/end! render$)
            (await (async/wait-for-store
                    store #(= "replacement-thumbnail" (get-in @store [:thumbnails visible :uri]))
                    "visible thumbnail is replaced"))
            (await (async/settle))
            (t/is (= [{:file-id (:id file) :object-id visible :tag "frame" :media blob}]
                     @uploaded))
            (t/is (= original-thumbnail (get-in @store [:thumbnails other]))))))))))

(t/deftest ^:async reconciled-library-refreshes-only-its-thumbnails
  (let [file        (cthf/sample-file :file1)
        file-id     (:id file)
        library     (assoc (cthf/sample-file :library) :library-of file-id :synced-at 1 :modified-at 1)
        library-id  (:id library)
        unrelated   (assoc (cthf/sample-file :unrelated) :library-of file-id :synced-at 1 :modified-at 1)
        store       (ths/setup-store file {:libraries [library unrelated]})
        thumbnail   (thc/fmt-object-id library-id (cthf/current-page-id library) (uuid/next) "component")
        other-thumb (thc/fmt-object-id (:id unrelated) (cthf/current-page-id unrelated) (uuid/next) "component")
        original    {:uri "original-thumbnail" :rendered-at nil}]
    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub
                 (fn [command params]
                   (if (and (= :get-file-object-thumbnails command)
                            (= {:file-id library-id :tag "component"} params))
                     (->> (rx/of {thumbnail "refreshed-thumbnail"})
                          (rx/observe-on :async))
                     (rx/throw (ex-info "Unexpected request" {:command command :params params})))))}
       (ptk/emit! store #(assoc % :thumbnails {other-thumb original})
                  (ptk/event ::dw/reconciled-file {:file-id library-id :tokens-changed false}))
       (await (async/wait-for-store store #(= "refreshed-thumbnail" (get-in @store [:thumbnails thumbnail :uri]))
                                    "reconciled library thumbnail"))
       (t/is (= original (get-in @store [:thumbnails other-thumb])))
       (t/is (= unrelated (get-in @store [:files (:id unrelated)])))))))
