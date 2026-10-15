;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.viewer-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.notifications :as ntf]
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

(defn- watch-events-after-update
  "Collect the events an event's watch emits, with the state the store
   would hand it: the store runs `update` first, so `watch` sees the
   result of the event's own update."
  [event state]
  (let [state (if (ptk/update? event) (ptk/update event state) state)]
    (watch-events event state)))

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
  (let [state (-> (base-state {:frames [{:selrect {:width 100 :height 100}}]
                               :index  0})
                  (assoc-in [:route :query-params :zoom] "fill"))]
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

(t/deftest toggle-chrome-hidden-hides-the-chrome-and-closes-thumbnails
  (let [state {:viewer-local {:chrome-hidden? false
                              :show-thumbnails true}}
        result (ptk/update dv/toggle-chrome-hidden state)]
    (t/is (true? (get-in result [:viewer-local :chrome-hidden?])))
    (t/is (false? (get-in result [:viewer-local :show-thumbnails])))))

(t/deftest toggle-chrome-hidden-toggles-back-and-defaults-to-visible
  (t/testing "a hidden chrome is shown again"
    (let [result (ptk/update dv/toggle-chrome-hidden
                             {:viewer-local {:chrome-hidden? true}})]
      (t/is (false? (get-in result [:viewer-local :chrome-hidden?])))))

  (t/testing "a viewer-local state without the key starts visible"
    (let [result (ptk/update dv/toggle-chrome-hidden {:viewer-local {}})]
      (t/is (true? (get-in result [:viewer-local :chrome-hidden?]))))))

(t/deftest toggle-chrome-hidden-hints-only-when-the-chrome-disappears
  (t/testing "hiding emits the hint"
    (let [events (watch-events-after-update dv/toggle-chrome-hidden
                                            {:viewer-local {:chrome-hidden? false}})]
      (t/is (= 1 (count events)))
      (t/is (ptk/type? ::ntf/show (first events)))))

  (t/testing "showing emits nothing"
    (t/is (empty? (watch-events-after-update dv/toggle-chrome-hidden
                                             {:viewer-local {:chrome-hidden? true}})))))

(t/deftest show-chrome-only-acts-when-the-chrome-is-hidden
  (t/testing "visible chrome: escape leaves the state untouched"
    (let [state {:viewer-local {:chrome-hidden? false}}]
      (t/is (= state (ptk/update dv/show-chrome state)))))

  (t/testing "hidden chrome: escape brings it back"
    (let [result (ptk/update dv/show-chrome {:viewer-local {:chrome-hidden? true}})]
      (t/is (false? (get-in result [:viewer-local :chrome-hidden?]))))))

(t/deftest initialize-resets-chrome-hidden
  (t/testing "a new viewer visit starts with the chrome visible"
    (let [state {:viewer-local {:chrome-hidden? true}}
          event (dv/initialize {:file-id page-id})
          result (ptk/update event state)]
      (t/is (false? (get-in result [:viewer-local :chrome-hidden?]))))))
