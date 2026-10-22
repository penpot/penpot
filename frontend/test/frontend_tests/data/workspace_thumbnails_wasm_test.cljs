;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-thumbnails-wasm-test
  (:require
   [app.common.thumbnails :as thc]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.thumbnails :as thumbnails]
   [app.main.data.workspace.thumbnails-wasm :as thumbnails-wasm]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [app.util.timers :as timers]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as a]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- thumbnail-state
  [file-id page-id frame-id]
  {:current-file-id file-id
   :current-page-id page-id
   :files {file-id {:data {:pages-index
                           {page-id {:objects {frame-id {:id frame-id
                                                         :type :frame
                                                         :shapes []
                                                         :selrect {:width 100 :height 100}}}}}}}}})

(defn- ^:async run-frames
  [frames]
  (await (a/settle))
  (let [callbacks (vals @frames)]
    (reset! frames {})
    (doseq [callback callbacks]
      (callback 0)))
  (await (a/settle)))

(defn- ^:async check-cancellation
  [stop-type phase]
  (let [file-id   (uuid/next)
        page-id   (uuid/next)
        frame-id  (uuid/next)
        ;; Library thumbnails upload their subtree before requesting a frame.
        state     (cond-> (thumbnail-state file-id page-id frame-id)
                    (not= phase :current-frame)
                    (assoc :current-file-id (uuid/next) :current-page-id (uuid/next)))
        stream    (rx/subject)
        frames    (atom {})
        uploaded  (atom nil)
        scene     (atom #{frame-id})
        errors    (atom [])
        result    (atom {:events [] :completed false})]
    (await
     (mock/with-mocks*
       {st/state (atom state)
        timers/raf (mock/stub (fn [callback]
                                (let [id (uuid/next)]
                                  (swap! frames assoc id callback)
                                  id)))
        timers/cancel-af! (mock/stub #(swap! frames dissoc %))
        wasm.api/set-objects (mock/stub (fn [_ callback] (reset! uploaded callback)))
        wasm.api/get-shape-extrect
        (mock/stub (fn [id]
                     (if (contains? @scene id)
                       {:width 100 :height 100}
                       (do
                         (swap! errors conj id)
                         (throw (js/Error. "Shape not found"))))))}
       (let [subscription
             (rx/subs! #(swap! result update :events conj %)
                       #(swap! result assoc :error %)
                       #(swap! result assoc :completed true)
                       (ptk/watch (thumbnails-wasm/render-thumbnail file-id page-id frame-id)
                                  state stream))]
         (try
           (await (a/settle))
           (when (= phase :library-frame)
             (@uploaded nil))
           (rx/push! stream
                     (if (= stop-type ::thumbnails/clear-thumbnail)
                       (thumbnails/clear-thumbnail
                        file-id (thc/fmt-object-id file-id page-id frame-id "component"))
                       (ptk/data-event stop-type)))
           ;; The next workspace can still have the library in :files, but
           ;; its WASM scene no longer contains the uploaded library main.
           (reset! scene #{})
           (swap! st/state assoc :current-file-id (uuid/next) :current-page-id (uuid/next))
           (when (= phase :library-upload)
             (@uploaded nil))
           (await (run-frames frames))
           (t/is (empty? @errors) "old thumbnails never query the new WASM scene")
           (t/is (= {:events [] :completed true} @result)
                 "cancellation ends the entire request, including an outstanding upload")
           (finally
             (rx/dispose! subscription)
             (rx/end! stream))))))))

(t/deftest ^:async navigation-cancels-a-pending-thumbnail-frame
  (await (check-cancellation :app.main.data.workspace/finalize-workspace :current-frame))
  (await (check-cancellation :app.main.data.workspace.pages/finalize-page :current-frame))
  (await (check-cancellation :app.main.data.workspace/finalize-workspace :library-frame))
  (await (check-cancellation :app.main.data.workspace.pages/finalize-page :library-frame)))

(t/deftest ^:async navigation-cancels-a-pending-library-thumbnail-upload
  (await (check-cancellation :app.main.data.workspace/finalize-workspace :library-upload))
  (await (check-cancellation :app.main.data.workspace.pages/finalize-page :library-upload)))

(t/deftest ^:async clearing-a-thumbnail-cancels-its-frame-or-upload
  (await (check-cancellation ::thumbnails/clear-thumbnail :current-frame))
  (await (check-cancellation ::thumbnails/clear-thumbnail :library-upload)))

(t/deftest ^:async a-library-thumbnail-renders-and-the-request-completes
  (let [file-id   (uuid/next)
        page-id   (uuid/next)
        frame-id  (uuid/next)
        state     (assoc (thumbnail-state file-id page-id frame-id)
                         :current-page-id (uuid/next))
        stream    (rx/subject)
        frames    (atom {})
        uploaded  (atom nil)
        events    (atom [])
        uri       "data:image/png;base64,AQID"]
    (await
     (mock/with-mocks*
       {st/state (atom state)
        timers/raf (mock/stub (fn [callback] (swap! frames assoc :frame callback) :frame))
        timers/cancel-af! (mock/stub #(swap! frames dissoc %))
        wasm.api/set-objects (mock/stub (fn [_ callback] (reset! uploaded callback)))
        wasm.api/get-shape-extrect (mock/stub (constantly {:width 100 :height 100}))
        wasm.api/render-shape-pixels (mock/stub (fn [_ _ _] (js/Uint8Array. #js [1 2 3])))
        (.-FileReader js/globalThis)
        (fn []
          (this-as reader
                   (set! (.-readAsDataURL reader)
                         (fn [_]
                           (.then (js/Promise.resolve nil)
                                  (fn [_]
                                    (set! (.-result reader) uri)
                                    ((.-onload reader))))))
                   nil))}
       (let [finished (a/observe
                       (ptk/watch (thumbnails-wasm/render-thumbnail file-id page-id frame-id)
                                  state stream)
                       :on-next #(swap! events conj %))]
         (await (a/settle))
         (rx/push! stream (thumbnails/clear-thumbnail file-id "another-thumbnail"))
         (@uploaded nil)
         (await (run-frames frames))
         (await finished)
         (let [result (reduce #(ptk/update %2 %1) state @events)
               object-id (thc/fmt-object-id file-id page-id frame-id "component")]
           (t/is (= uri (get-in result [:thumbnails object-id :uri]))))
         (rx/end! stream))))))
