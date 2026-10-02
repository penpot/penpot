;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.export-preview-test
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom/client" :as rdc]
   ["react-dom/server" :as rds]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.store :as st]
   [app.main.ui.exports.preview :as preview]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.inspect.exports :as inspect-exports]
   [app.main.ui.workspace.sidebar.options.menus.exports :as exports]
   [app.render-wasm.api :as wasm.api]
   [app.util.globals :as globals]
   [app.util.storage :as storage]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.mock :as mock]
   [rumext.v2 :as mf]))

(defn- render-menu
  [shapes presets]
  (with-redefs [hooks/use-portal-container (fn ([] nil) ([_] nil))]
    (rds/renderToStaticMarkup
     (mf/element exports/exports-menu*
                 #js {:ids (mapv :id shapes)
                      :shapes shapes
                      :type (if (= 1 (count shapes)) :rect :multiple)
                      :values {:exports presets}
                      :fileId (uuid/next)
                      :pageId (uuid/next)}))))

(defn- ^:async with-mounted-menu
  [presets test-fn]
  (let [dom          (JSDOM. "<!doctype html><html><body><div id='root'></div></body></html>")
        window       (.-window dom)
        document     (.-document window)
        old-window   (.-window js/globalThis)
        old-document (.-document js/globalThis)
        old-act      (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
        old-state    @st/state
        file-id      (uuid/next)
        page-id      (uuid/next)
        shape        (cts/setup-shape {:type :rect :width 100 :height 50 :exports presets})
        container    (.getElementById document "root")]
    (set! (.-window js/globalThis) window)
    (set! (.-document js/globalThis) document)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (reset! st/state {:current-file-id file-id :current-page-id page-id
                      :files {file-id {:data {:pages-index {page-id {:objects {(:id shape) shape}}}}}}
                      :viewer {:pages {page-id {:objects {(:id shape) shape}}}}})
    (try
      (await
       (mock/with-mocks* {globals/document document globals/window window
                          storage/user (storage/create-storage nil "export-preview-test")}
         (let [root (rdc/createRoot container)
               render-selection
               (fn [section]
                 (.render root
                          (case section
                            :design (mf/element exports/exports-menu*
                                                #js {:ids [(:id shape)] :shapes [shape] :type :rect
                                                     :values {:exports presets} :fileId file-id :pageId page-id})
                            (:inspect :viewer) (mf/html
                                                [:& inspect-exports/exports
                                                 {:shapes [shape] :type :rect :file-id file-id :page-id page-id
                                                  :from (if (= section :viewer) :viewer :workspace)}])
                            nil)))]
           (try
             (await (react/act #(render-selection :design)))
             (await (test-fn container render-selection))
             (finally
               (await (react/act #(.unmount root))))))))
      (finally
        (reset! st/state old-state)
        (.close window)
        (set! (.-window js/globalThis) old-window)
        (set! (.-document js/globalThis) old-document)
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) old-act)))))

(t/deftest ^:async reopening-export-keeps-the-last-preview-expansion
  (await
   (with-mounted-menu
     [{:type :png :scale 1 :suffix ""}]
     (^:async fn [container _render-selection]
       (let [preview-button #(.querySelector container "button[aria-controls^='export-preview-']")
             export-button (.querySelector container "button")]
         (await (react/act #(.click (preview-button))))
         (t/is (= "true" (.getAttribute (preview-button) "aria-expanded")))
         (await (react/act #(.click export-button)))
         (t/is (nil? (preview-button)))
         (await (react/act #(.click export-button)))
         (t/is (= "true" (.getAttribute (preview-button) "aria-expanded")))
         (await (react/act #(.click (preview-button))))
         (await (react/act #(.click export-button)))
         (await (react/act #(.click export-button)))
         (t/is (= "false" (.getAttribute (preview-button) "aria-expanded"))))))))

(t/deftest ^:async preview-preset-uses-the-penpot-select
  (await
   (with-mounted-menu
     [{:type :png :scale 1 :suffix ""} {:type :pdf :scale 1 :suffix ""}]
     (^:async fn [container _render-selection]
       (await (react/act #(.click (.querySelector container "button[aria-controls^='export-preview-']"))))
       (let [panel (.querySelector container "div[id^='export-preview-']")
             select (.querySelector panel "button[role='combobox']")]
         (t/is (some? select))
         (t/is (nil? (.querySelector panel "select")))
         (when select
           (await (react/act #(.click select)))
           (let [option (aget (.querySelectorAll panel "[role='option']") 1)]
             (await (react/act #(.click option)))
             (t/is (str/includes? (.-textContent panel) "100 × 50 · PDF")))))))))

(t/deftest ^:async reselecting-a-layer-keeps-the-preview-expansion
  (await
   (with-mounted-menu
     [{:type :png :scale 1 :suffix ""}]
     (^:async fn [container render-selection]
       (let [preview-button #(.querySelector container "button[aria-controls^='export-preview-']")]
         (await (react/act #(.click (preview-button))))
         (await (react/act #(render-selection nil)))
         (t/is (nil? (preview-button)))
         (await (react/act #(render-selection :design)))
         (t/is (= "true" (.getAttribute (preview-button) "aria-expanded")))
         (await (react/act #(.click (preview-button))))
         (await (react/act #(render-selection nil)))
         (await (react/act #(render-selection :design)))
         (t/is (= "false" (.getAttribute (preview-button) "aria-expanded"))))))))

(t/deftest ^:async inspect-shows-the-preview-and-keeps-its-expansion-between-tabs
  (await
   (with-mounted-menu
     [{:type :png :scale 1 :suffix ""}]
     (^:async fn [container render-selection]
       (let [preview-button #(.querySelector container "button[aria-controls^='export-preview-']")]
         (await (react/act #(.click (preview-button))))
         (await (react/act #(render-selection :inspect)))
         (t/is (some? (preview-button)))
         (when (preview-button)
           (t/is (= "true" (.getAttribute (preview-button) "aria-expanded")))
           (t/is (some? (.querySelector container "svg[id^='screenshot-']")))
           (await (react/act #(.click (preview-button))))
           (await (react/act #(render-selection :design)))
           (t/is (= "false" (.getAttribute (preview-button) "aria-expanded")))))))))

(t/deftest ^:async viewer-inspect-previews-the-viewer-page-without-workspace-objects
  (await
   (with-mounted-menu
     [{:type :png :scale 1 :suffix ""}]
     (^:async fn [container render-selection]
       (await (react/act #(swap! st/state assoc :files {} :features #{"render-wasm/v1"})))
       (await (react/act #(render-selection :viewer)))
       (let [button (.querySelector container "button[aria-controls^='export-preview-']")]
         (t/is (some? button))
         (when button
           (await (react/act #(.click button)))
           (t/is (some? (.querySelector container "svg[id^='screenshot-']")))
           (t/is (str/includes? (.-textContent container) "100 × 50 · PNG"))))))))

(t/deftest single-shape-with-export-offers-a-preview
  (let [presets [{:type :png :scale 1 :suffix ""}]
        shape   (cts/setup-shape {:type :rect :exports presets})
        markup  (render-menu [shape] presets)]
    (t/is (str/includes? markup "aria-controls=\"export-preview-"))
    (t/is (str/includes? markup "aria-expanded=\"false\""))))

(t/deftest preview-requires-an-export-preset
  (let [shape (cts/setup-shape {:type :rect})]
    (t/is (not (str/includes? (render-menu [shape] [])
                              "aria-controls=\"export-preview-")))))

(t/deftest multiple-selection-does-not-imply-a-combined-export-preview
  (let [presets [{:type :png :scale 1 :suffix ""}]
        shapes  [(cts/setup-shape {:type :rect :exports presets})
                 (cts/setup-shape {:type :rect :exports presets})]]
    (t/is (not (str/includes? (render-menu shapes presets)
                              "aria-controls=\"export-preview-")))))

(t/deftest preview-keeps-the-export-size-while-bounding-the-render-size
  (doseq [type [:png :jpeg :webp]]
    (let [settings (preview/preview-settings {:width 1200 :height 800}
                                             {:type type :scale 4})]
      (t/is (= {:type type :scale 0.2 :width 4800 :height 3200} settings)))))

(t/deftest preview-respects-small-raster-export-scales
  (t/is (= {:type :png :scale 0.5 :width 50 :height 25}
           (preview/preview-settings {:width 100 :height 50} {:type :png :scale 0.5}))))

(t/deftest vector-previews-ignore-a-raster-preset-scale
  (doseq [type [:svg :pdf]]
    (t/is (= {:type :png :scale 1 :width 100 :height 50}
             (preview/preview-settings {:width 100 :height 50} {:type type :scale 4})))))

(t/deftest empty-export-bounds-do-not-create-an-infinite-render-scale
  (t/is (= {:type :png :scale 1 :width 0 :height 0}
           (preview/preview-settings {:width 0 :height 0} {:type :png :scale 1}))))

(t/deftest classic-preview-matches-the-rounded-svg-export-dimensions
  (let [shape    (cts/setup-shape {:type :frame :x 282 :y 300
                                   :width 400.000015 :height 260.000015})
        bounds   (preview/preview-bounds {(:id shape) shape} shape false)
        settings (preview/preview-settings bounds {:type :png :scale 1})]
    (t/is (= 400 (:width settings)))
    (t/is (= 260 (:height settings)))))

(t/deftest wasm-preview-uses-native-bounds-without-rounding-away-real-fractions
  (let [shape (cts/setup-shape {:type :frame :width 400 :height 260})]
    (with-redefs [wasm.api/initialized? (constantly true)
                  wasm.api/get-shape-extrect (fn [_] {:width 400.5 :height 260.25})]
      (let [bounds   (preview/preview-bounds {(:id shape) shape} shape true)
            settings (preview/preview-settings bounds {:type :png :scale 2})]
        (t/is (= 801 (:width settings)))
        (t/is (= 521 (:height settings)))))))
