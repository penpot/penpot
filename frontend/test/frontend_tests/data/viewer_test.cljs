;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.viewer-test
  (:require
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.viewer :as dv]
   [app.main.router :as rt]
   [beicon.v2.core :as rx]
   [cljs.test :as t]
   [potok.v2.core :as ptk]))

(def ^:private page-id
  (uuid/custom 1 1))

(defn- base-state
  "Build a minimal viewer state with the given frames and query-params."
  [{:keys [frames index]}]
  {:route {:params {:query {:page-id (str page-id)
                            :index   (str index)}}}
   :viewer {:pages {page-id {:frames frames}}}
   :viewer-local {:viewport-size {:width 1000 :height 800}}})

(t/deftest zoom-to-fit-clamps-out-of-bounds-index
  (t/testing "index exceeds frame count"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}]
                             :index  1})
          result (ptk/update dv/zoom-to-fit state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fit))
      (t/is (number? (get-in result [:viewer-local :zoom])))))

  (t/testing "index is zero with single frame (normal case)"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}]
                             :index  0})
          result (ptk/update dv/zoom-to-fit state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fit))
      (t/is (number? (get-in result [:viewer-local :zoom])))))

  (t/testing "index within valid range with multiple frames"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}
                                      {:selrect {:width 200 :height 200}}]
                             :index  1})
          result (ptk/update dv/zoom-to-fit state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fit))
      (t/is (number? (get-in result [:viewer-local :zoom]))))))

(t/deftest zoom-to-fill-clamps-out-of-bounds-index
  (t/testing "index exceeds frame count"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}]
                             :index  1})
          result (ptk/update dv/zoom-to-fill state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fill))
      (t/is (number? (get-in result [:viewer-local :zoom])))))

  (t/testing "index is zero with single frame (normal case)"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}]
                             :index  0})
          result (ptk/update dv/zoom-to-fill state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fill))
      (t/is (number? (get-in result [:viewer-local :zoom])))))

  (t/testing "index within valid range with multiple frames"
    (let [state (base-state {:frames [{:selrect {:width 100 :height 100}}
                                      {:selrect {:width 200 :height 200}}]
                             :index  1})
          result (ptk/update dv/zoom-to-fill state)]
      (t/is (= (get-in result [:viewer-local :zoom-type]) :fill))
      (t/is (number? (get-in result [:viewer-local :zoom]))))))

(defn- watch-events
  "Collect the events an event's watch emits synchronously."
  [event state]
  (let [out (atom [])]
    (some-> (ptk/watch event state nil)
            (rx/subscribe #(swap! out conj %)))
    @out))

(defn- zoom-state
  "Build a viewer state with the given `:zoom` query param and zoom type."
  [zoom-param zoom-type]
  {:route {:params {:query (cond-> {:page-id (str page-id) :index "0"}
                             (some? zoom-param)
                             (assoc :zoom zoom-param))}}
   :viewer-local (cond-> {}
                   (some? zoom-type)
                   (assoc :zoom-type zoom-type))})

(t/deftest update-zoom-querystring-does-not-navigate-when-url-already-matches
  (t/testing "zoom type already described by the query string"
    (t/is (empty? (watch-events dv/update-zoom-querystring
                                (zoom-state "fit" :fit)))))

  (t/testing "no zoom type and no zoom query param"
    (t/is (empty? (watch-events dv/update-zoom-querystring
                                (zoom-state nil nil))))))

(t/deftest update-zoom-querystring-navigates-when-zoom-changes
  (t/testing "zoom type differs from the query string"
    (let [events (watch-events dv/update-zoom-querystring
                               (zoom-state "fit" :fill))
          {:keys [id params options]} (some-> (first events) deref)]
      (t/is (= 1 (count events)))
      (t/is (= :viewer id))
      (t/is (= :fill (:zoom params)))
      (t/is (true? (::rt/replace options)))))

  (t/testing "zoom query param absent, other params preserved"
    (let [events (watch-events dv/update-zoom-querystring
                               (zoom-state nil :fit))
          {:keys [params]} (some-> (first events) deref)]
      (t/is (= 1 (count events)))
      (t/is (= :fit (:zoom params)))
      (t/is (= (str page-id) (:page-id params)))
      (t/is (= "0" (:index params)))))

  (t/testing "zoom type cleared drops the query param"
    (let [events (watch-events dv/update-zoom-querystring
                               (zoom-state "fit" nil))
          {:keys [params]} (some-> (first events) deref)]
      (t/is (= 1 (count events)))
      (t/is (not (contains? params :zoom))))))

(t/deftest bundle-fetched-with-zoom-fill-url-does-not-navigate
  ;; Regression test for the React "maximum update depth exceeded"
  ;; error (2.18.0-RC5): loading the viewer with a URL that already
  ;; contains `zoom=fill` re-entered the cycle zoom-to-fill →
  ;; update-zoom-querystring → nav → navigated → zoom-to-fill…,
  ;; because update-zoom-querystring navigated unconditionally.
  ;; At HEAD the guard breaks the cycle, so a bundle fetch must not
  ;; emit any navigation.
  (let [state (as-> (base-state {:frames [{:selrect {:width 100 :height 100}}]
                                 :index  0}) state
                ;; The router stores the same query map under both keys.
                (let [query (assoc (rt/get-params state) :zoom "fill")]
                  (-> state
                      (assoc-in [:route :params :query] query)
                      (assoc-in [:route :query-params] query))))]
    (let [events (watch-events (dv/bundle-fetched
                                {:file {:id page-id
                                        :data {:pages [page-id]
                                               :pages-index {page-id {:objects {}}}}}
                                 :project {}
                                 :team {:features []}
                                 :share-links []
                                 :libraries []
                                 :users []
                                 :permissions {}
                                 :thumbnails {}})
                               state)
          ;; `update-page-position-data` and `go-to-frame-auto` are part
          ;; of the normal init sequence; the navigation events are the
          ;; ones that can restart the zoom cycle.
          navigations (filter #(= :app.main.router/navigate (ptk/type %)) events)]
      (t/is (empty? navigations)))))

(defn- minimal-bundle
  [page-ids]
  {:file {:id (uuid/custom 2 2)
          :data {:pages (vec page-ids)
                 :pages-index (into {} (map (fn [id] [id {:objects {}}])) page-ids)}}
   :project {}
   :team {:features []}
   :share-links []
   :libraries []
   :users []
   :permissions {}
   :thumbnails {}})

(defn- route-without-page-id
  []
  (let [query {:file-id (str (uuid/custom 2 2)) :section "interactions" :index "0"}]
    {:route {:params {:query query} :query-params query}}))

(t/deftest initialize-without-page-id-fetches-the-bundle
  ;; The viewer route passes `:page-id nil` when the URL has none; the
  ;; params check used to reject it before any fetch.
  (let [events (watch-events (dv/initialize {:file-id (uuid/custom 2 2)
                                             :page-id nil
                                             :share-id nil})
                             {})]
    (t/is (= [::dv/fetch-bundle] (map ptk/type (remove nil? events))))))

(t/deftest bundle-fetched-without-page-id-names-the-first-page
  (t/testing "the route is replaced by one naming the first page"
    (let [first-page (uuid/custom 3 1)
          events (watch-events (dv/bundle-fetched
                                (minimal-bundle [first-page (uuid/custom 3 2)]))
                               (route-without-page-id))
          {:keys [id params options]} (some-> (first events) deref)]
      (t/is (= 1 (count events)))
      (t/is (= :viewer id))
      (t/is (= first-page (:page-id params)))
      (t/is (= "interactions" (:section params)))
      (t/is (true? (::rt/replace options)))))

  (t/testing "a file without pages keeps the route"
    (let [events (watch-events (dv/bundle-fetched (minimal-bundle []))
                               (route-without-page-id))
          navigations (filter #(= :app.main.router/navigate (ptk/type %)) events)]
      (t/is (empty? navigations)))))

;; --- Live updates

(def ^:private file-id
  (uuid/custom 1 2))

(def ^:private page-id-2
  (uuid/custom 1 4))

(defn- make-page
  [pid frame-ids]
  (let [root   (cts/setup-shape {:id uuid/zero
                                 :type :frame
                                 :name "Root"
                                 :shapes (vec frame-ids)})
        frames (map #(cts/setup-shape {:id %
                                       :type :frame
                                       :name "Board"
                                       :parent-id uuid/zero
                                       :frame-id uuid/zero})
                    frame-ids)]
    {:id pid
     :objects (into {uuid/zero root}
                    (map (juxt :id identity))
                    frames)}))

(defn- make-file
  [{:keys [revn vern frames-1 frames-2] :or {revn 5 vern 0}}]
  {:id file-id
   :revn revn
   :vern vern
   :data {:pages [page-id page-id-2]
          :pages-index {page-id   (make-page page-id frames-1)
                        page-id-2 (make-page page-id-2 frames-2)}}})

(defn- live-state
  [file index]
  {:route {:params {:query {:page-id (str page-id) :index (str index)}}}
   :files {file-id file}
   :viewer {:file file
            :pages (#'dv/build-viewer-pages file)}})

(defn- hide-board-change
  [frame-id]
  {:type :mod-obj
   :id frame-id
   :page-id page-id
   :operations [{:type :set :attr :hide-in-viewer :val true}]})

(defn- refresh-requested?
  [event]
  (= ::dv/refresh-requested (ptk/type event)))

(t/deftest file-change-is-applied-to-the-viewer
  (let [f1      (uuid/custom 1 3)
        f2      (uuid/custom 1 5)
        g1      (uuid/custom 1 6)
        file    (make-file {:frames-1 [f1 f2] :frames-2 [g1]})
        state   (live-state file 0)
        message {:file-id file-id
                 :revn 6
                 :vern 0
                 :changes [(hide-board-change f1)]}
        events  (watch-events (#'dv/handle-file-change message) state)
        updated (ptk/update (first events) state)]

    (t/is (= 1 (count events)))
    (t/is (not (refresh-requested? (first events))))
    (t/is (= 6 (get-in updated [:viewer :file :revn])))
    (t/is (= 6 (get-in updated [:files file-id :revn])))
    (t/is (= [f2] (mapv :id (get-in updated [:viewer :pages page-id :frames]))))

    (t/testing "the board being viewed is remembered"
      (t/is (= f1 (:viewer-sync-frame-id updated))))

    (t/testing "pages not touched by the changes are not calculated again"
      (t/is (identical? (get-in state [:viewer :pages page-id-2])
                        (get-in updated [:viewer :pages page-id-2]))))))

(t/deftest file-change-detects-out-of-sync
  (let [f1    (uuid/custom 1 3)
        file  (make-file {:frames-1 [f1] :frames-2 []})
        state (live-state file 0)
        run   (fn [message]
                (watch-events (#'dv/handle-file-change
                               (merge {:file-id file-id :vern 0 :changes []} message))
                              state))]

    (t/testing "a gap in the revisions refreshes the file"
      (let [events (run {:revn 8})]
        (t/is (= 1 (count events)))
        (t/is (refresh-requested? (first events)))))

    (t/testing "other version of the file refreshes the file"
      (let [events (run {:revn 6 :vern 1})]
        (t/is (= 1 (count events)))
        (t/is (refresh-requested? (first events)))))

    (t/testing "changes already included in the viewer file are ignored"
      (t/is (empty? (run {:revn 5})))
      (t/is (empty? (run {:revn 4}))))

    (t/testing "changes of other files are ignored"
      (t/is (empty? (run {:file-id (uuid/custom 9 9) :revn 6}))))))

(t/deftest file-change-that-cannot-be-applied-refreshes-the-file
  (let [f1      (uuid/custom 1 3)
        file    (make-file {:frames-1 [f1] :frames-2 []})
        state   (live-state file 0)
        message {:file-id file-id
                 :revn 6
                 :vern 0
                 ;; invalid change: unknown type
                 :changes [{:type :this-is-not-a-change :page-id page-id}]}
        events  (watch-events (#'dv/handle-file-change message) state)]
    (t/is (= 1 (count events)))
    (t/is (refresh-requested? (first events)))))

(t/deftest changes-of-unknown-pages-are-not-applied
  (let [f1       (uuid/custom 1 3)
        file     (make-file {:frames-1 [f1] :frames-2 []})
        unknown  (uuid/custom 7 7)
        changes  [(hide-board-change f1)
                  (assoc (hide-board-change f1) :page-id unknown)]]
    (t/is (= [(hide-board-change f1)]
             (#'dv/filter-page-changes file changes)))))

(defn- navigations
  [events]
  (into []
        (comp (filter #(= ::rt/navigate (ptk/type %)))
              (map deref))
        events))

(t/deftest current-board-is-tracked-by-id
  (let [f0 (uuid/custom 1 7)
        f1 (uuid/custom 1 3)
        f2 (uuid/custom 1 5)]

    (t/testing "a board added before the current one does not change the board shown"
      (let [file   (make-file {:frames-1 [f0 f1 f2] :frames-2 []})
            state  (-> (live-state file 0)
                       (assoc :viewer-sync-frame-id f1))
            [nav :as navs] (navigations (watch-events @#'dv/after-file-updated state))]
        (t/is (= 1 (count navs)))
        (t/is (= 1 (get-in nav [:params :index])))
        (t/is (true? (::rt/replace (:options nav))))))

    (t/testing "the board is still in the same position"
      (let [file  (make-file {:frames-1 [f1 f2] :frames-2 []})
            state (-> (live-state file 0)
                      (assoc :viewer-sync-frame-id f1))]
        (t/is (empty? (navigations (watch-events @#'dv/after-file-updated state))))))

    (t/testing "the current board was removed: stay in range"
      (let [file  (make-file {:frames-1 [f0] :frames-2 []})
            state (-> (live-state file 2)
                      (assoc :viewer-sync-frame-id f2))
            [nav :as navs] (navigations (watch-events @#'dv/after-file-updated state))]
        (t/is (= 1 (count navs)))
        (t/is (= 0 (get-in nav [:params :index])))))))

(t/deftest zoom-is-recalculated-after-an-update
  (let [f1    (uuid/custom 1 3)
        file  (make-file {:frames-1 [f1] :frames-2 []})
        state (-> (live-state file 0)
                  (assoc :viewer-sync-frame-id f1)
                  (assoc-in [:viewer-local :zoom-type] :fit))
        types (mapv ptk/type (remove fn? (watch-events @#'dv/after-file-updated state)))]
    (t/is (some #{::dv/zoom-to-fit} types))))
