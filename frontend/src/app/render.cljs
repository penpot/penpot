;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render
  "The main entry point for UI part needed by the exporter."
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.logging :as log]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.types.components-list :as ctkl]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.main.data.fonts :as df]
   [app.main.features :as features]
   [app.main.rasterizer :as rasterizer]
   [app.main.render :as render]
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.util.dom :as dom]
   [app.util.globals :as glob]
   [app.util.object :as obj]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [garden.core :refer [css]]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [promesa.core :as p]
   [app.render-wasm.api :as wasm.api]
   [rumext.v2 :as mf]))

(log/setup! {:app :info})

(defn set-current-team
  [{:keys [id permissions features] :as team}]
  (ptk/reify ::set-current-team
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :permissions permissions)
          (update :teams assoc id team)
          (assoc :current-team-id id)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (features/initialize features)))))

(defn- fetch-team
  [& {:keys [file-id]}]
  (ptk/reify ::fetch-team
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (repo/cmd! :get-team {:file-id file-id})
           (rx/mapcat (fn [team]
                        (rx/of (set-current-team team)
                               (ptk/data-event ::team-fetched team))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPONENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private ref:objects
  (l/derived :objects st/state))

(mf/defc object-svg
  {::mf/wrap-props false}
  [{:keys [object-id embed skip-children]}]
  (let [objects (mf/deref ref:objects)]

    ;; Set the globa CSS to assign the page size, needed for PDF
    ;; exportation process.
    (mf/with-effect [objects]
      (when-let [object (get objects object-id)]
        (let [{:keys [width height]} (gsb/get-object-bounds [objects] object)]
          (dom/set-page-style!
           {:size (str/concat
                   (mth/ceil width) "px "
                   (mth/ceil height) "px")}))))

    (when objects
      [:& (mf/provider ctx/is-render?) {:value true}
       [:& render/object-svg
        {:objects objects
         :object-id object-id
         :embed embed
         :skip-children skip-children}]])))

(mf/defc objects-svg
  {::mf/wrap-props false}
  [{:keys [object-ids embed skip-children]}]
  (when-let [objects (mf/deref ref:objects)]
    (for [object-id object-ids]
      (let [objects (render/adapt-objects-for-shape objects object-id)]
        [:& (mf/provider ctx/is-render?) {:value true}
         [:& render/object-svg
          {:objects objects
           :key (str object-id)
           :object-id object-id
           :embed embed
           :skip-children skip-children}]]))))

(defn- fetch-objects-bundle
  [& {:keys [file-id page-id share-id object-id] :as options}]
  (ptk/reify ::fetch-objects-bundle
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (get state :features)]
        (->> (rx/zip
              (repo/cmd! :get-font-variants {:file-id file-id :share-id share-id})
              (repo/cmd! :get-page {:file-id file-id
                                    :page-id page-id
                                    :share-id share-id
                                    :object-id object-id
                                    :features features}))
             (rx/tap (fn [[fonts]]
                       (when (seq fonts)
                         (st/emit! (df/fonts-fetched fonts)))))
             (rx/observe-on :async)
             (rx/map (comp :objects second))
             (rx/map (fn [objects]
                       (let [objects (render/adapt-objects-for-shape objects object-id)]
                         #(assoc % :objects objects)))))))))

(def ^:private schema:render-objects
  [:map {:title "render-objets"}
   [:page-id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]
   [:embed {:optional true} :boolean]
   [:skip-children {:optional true} :boolean]
   [:object-id
    [:or [::sm/set ::sm/uuid] ::sm/uuid]]])

(def ^:private coerce-render-objects-params
  (sm/coercer schema:render-objects))

(defn- render-objects
  [params]
  (try
    (let [{:keys [file-id page-id embed share-id object-id skip-children] :as params}
          (coerce-render-objects-params params)]
      (st/emit! (fetch-objects-bundle :file-id file-id :page-id page-id :share-id share-id :object-id object-id))
      (if (uuid? object-id)
        (mf/html
         [:& object-svg
          {:file-id file-id
           :page-id page-id
           :share-id share-id
           :object-id object-id
           :embed embed
           :skip-children skip-children}])

        (mf/html
         [:& objects-svg
          {:file-id file-id
           :page-id page-id
           :share-id share-id
           :object-ids (into #{} object-id)
           :embed embed
           :skip-children skip-children}])))
    (catch :default cause
      (when-let [explain (-> cause ex-data ::sm/explain)]
        (js/console.log "Unexpected error")
        (js/console.log (sm/humanize-explain explain)))
      (mf/html [:span "Unexpected error:" (ex-message cause)]))))

;; ---- COMPONENTS SPRITE

(mf/defc components-svg
  {::mf/wrap-props false}
  [{:keys [embed component-id]}]
  (let [file-ref (mf/with-memo [] (l/derived :file st/state))
        state    (mf/use-state {:component-id component-id})]
    (when-let [file (mf/deref file-ref)]
      [:*
       [:style
        (css [[:body
               {:margin 0
                :overflow "hidden"
                :width "100vw"
                :height "100vh"}]

              [:main
               {:overflow "auto"
                :display "flex"
                :justify-content "center"
                :align-items "center"
                :height "calc(100vh - 200px)"}
               [:svg {:width "50%"
                      :height "50%"}]]
              [:.nav
               {:display "flex"
                :margin 0
                :padding "10px"
                :flex-direction "column"
                :flex-wrap "wrap"
                :height "200px"
                :list-style "none"
                :overflow-x "scroll"
                :border-bottom "1px dotted #e6e6e6"}
               [:a {:cursor :pointer
                    :text-overflow "ellipsis"
                    :white-space "nowrap"
                    :overflow "hidden"
                    :text-decoration "underline"}]
               [:li {:display "flex"
                     :width "150px"
                     :padding "5px"
                     :border "0px solid black"}]]])]

       [:ul.nav
        (for [[id data] (ctkl/components (:data file))]
          (let [on-click (fn [event]
                           (dom/prevent-default event)
                           (swap! state assoc :component-id id))]
            [:li {:key (str id)}
             [:a {:on-click on-click} (:name data)]]))]

       [:main
        [:& render/components-svg
         {:data (:data file)
          :embed embed}

         (when-let [component-id (:component-id @state)]
           [:use {:x 0 :y 0 :href (str "#" component-id)}])]]])))

(defn- fetch-components-bundle
  [& {:keys [file-id]}]
  (ptk/reify ::fetch-components-bundle
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (get state :features)]
        (->> (repo/cmd! :get-file {:id file-id :features features})
             (rx/map (fn [file] #(assoc % :file file))))))))

(def ^:private schema:render-components
  [:map {:title "render-components"}
   [:file-id ::sm/uuid]
   [:embed {:optional true} :boolean]
   [:component-id {:optional true} ::sm/uuid]])

(def ^:private coerce-render-components-params
  (sm/coercer schema:render-components))

(defn render-components
  [params]
  (try
    (let [{:keys [file-id component-id embed] :as params}
          (coerce-render-components-params params)]

      (st/emit! (ptk/reify ::initialize-render-components
                  ptk/WatchEvent
                  (watch [_ _ stream]
                    (rx/merge
                     (rx/of (fetch-team :file-id file-id))

                     (->> stream
                          (rx/filter (ptk/type? ::team-fetched))
                          (rx/observe-on :async)
                          (rx/map (constantly params))
                          (rx/map fetch-components-bundle))))))

      (mf/html
       [:& components-svg
        {:component-id component-id
         :embed embed}]))

    (catch :default cause
      (when-let [explain (-> cause ex-data ::sm/explain)]
        (js/console.log "Unexpected error")
        (js/console.log (sm/humanize-explain explain)))
      (mf/html [:span "Unexpected error:" (ex-message cause)]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SETUP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce app-root
  (let [el (dom/get-element "app")]
    (mf/create-root el)))

(declare ^:private render-single-object)
(declare ^:private render-components)
(declare ^:private render-objects)

(defn- parse-params
  [loc]
  (let [href (unchecked-get loc "href")]
    (some-> href u/uri :query u/query-string->map)))

(def ^:private default-jpeg-quality 95)

(defn- rx->promise
  [stream]
  (p/create (fn [resolve reject]
              (rx/subs! resolve reject stream))))

(defn- normalize-object-id
  [value]
  (cond
    (uuid? value) value
    (string? value) (uuid/parse* value)
    :else nil))

(defn- normalize-scale
  [value]
  (let [scale (d/parse-double value 1)]
    (if (and (number? scale) (pos? scale)) scale 1)))

(defn- normalize-type
  [value]
  (let [type (keyword (d/nilv value "png"))]
    (if (#{:png :jpeg :webp} type) type :png)))

(defn- normalize-quality
  [value]
  (when-let [quality (d/parse-integer value nil)]
    (mth/clamp quality 0 100)))

(defn- encode-quality
  [quality]
  (/ (mth/clamp (or quality default-jpeg-quality) 0 100) 100))

(defn- export-mime-type
  [type]
  (case type
    :jpeg "image/jpeg"
    :png "image/png"
    :webp "image/webp"
    nil))

(defn- parse-export-request
  [payload]
  (let [object-id (normalize-object-id (unchecked-get payload "objectId"))
        scale (normalize-scale (unchecked-get payload "scale"))
        type (normalize-type (unchecked-get payload "type"))
        quality (normalize-quality (unchecked-get payload "quality"))
        skip-children (boolean (unchecked-get payload "skipChildren"))]
    (when-not object-id
      (throw (js/Error. "Export request is missing a valid objectId.")))
    {:object-id object-id
     :scale scale
     :type type
     :quality quality
     :skip-children skip-children}))

(defn- object-bounds
  [objects object-id]
  (when-let [shape (get objects object-id)]
    (gsb/get-object-bounds objects shape {:ignore-margin? false})))

(defn- object-node
  [object-id]
  (let [node (dom/get-element (clojure.core/str "screenshot-" object-id))]
    (when-not node
      (throw (js/Error. "Export SVG node not found in the render DOM.")))
    node))

(defn- svg-with-background
  [svg-node]
  (let [clone (.cloneNode svg-node true)
        rect (dom/make-node "http://www.w3.org/2000/svg" "rect")]
    (dom/set-property! rect "x" "0")
    (dom/set-property! rect "y" "0")
    (dom/set-property! rect "width" "100%")
    (dom/set-property! rect "height" "100%")
    (dom/set-property! rect "fill" "#ffffff")
    (if-let [first-child (.-firstChild clone)]
      (.insertBefore clone rect first-child)
      (dom/append-child! clone rect))
    clone))

(defn- render-with-rasterizer
  [{:keys [object-id scale type quality]}]
  (let [objects (deref ref:objects)
        bounds (object-bounds objects object-id)
        width (:width bounds)
        target-width (mth/ceil (* (d/nilv width 0) scale))
        svg-node (object-node object-id)
        svg-node (if (= type :jpeg) (svg-with-background svg-node) svg-node)
        svg-data (dom/node->xml svg-node)
        payload (d/without-nils
                 {:data svg-data
                  :styles ""
                  :width target-width
                  :result "blob"
                  :format (export-mime-type type)
                  :encode-quality (when (= type :jpeg) (encode-quality quality))})]
    (when-not (and (some? svg-data) (pos? target-width))
      (throw (js/Error. "Export rasterizer requires a valid SVG and dimensions.")))
    (p/let [blob (rx->promise (rasterizer/render payload))
            data-url (rx->promise (wapi/read-file-as-data-url blob))]
      data-url)))

(defn- render-with-wasm
  [{:keys [object-id scale type quality skip-children]}]
  (p/let [module-ok? @wasm.api/module]
    (when-not module-ok?
      (throw (js/Error. "Render-WASM module failed to initialize.")))
    (let [objects (deref ref:objects)]
      (when-not objects
        (throw (js/Error. "Render objects not loaded yet.")))
      (let [objects (if (and skip-children (contains? objects object-id))
                      (update objects object-id #(assoc % :shapes []))
                      objects)
            bounds (object-bounds objects object-id)
            base-width (:width bounds)
            base-height (:height bounds)
            target-width (mth/ceil (* (d/nilv base-width 0) scale))
            target-height (mth/ceil (* (d/nilv base-height 0) scale))]
        (when-not (and (pos? target-width) (pos? target-height))
          (throw (js/Error. "Render-WASM requires valid bounds.")))
        (let [zoom (/ target-width base-width)
              canvas (wapi/create-offscreen-canvas target-width target-height)
              init? (wasm.api/init-canvas-context canvas)
              alpha (if (= type :jpeg) 1 0)
              options (let [opts (obj/create)]
                        (when-let [mtype (export-mime-type type)]
                          (obj/set! opts "type" mtype))
                        (when (= type :jpeg)
                          (obj/set! opts "quality" (encode-quality quality)))
                        opts)]
          (when-not init?
            (throw (js/Error. "Render-WASM canvas initialization failed.")))
          (-> (p/create
               (fn [resolve reject]
                 (let [on-render
                       (fn []
                         (-> (wapi/create-blob-from-canvas canvas options)
                             (p/then #(rx->promise (wapi/read-file-as-data-url %)))
                             (p/then resolve)
                             (p/catch reject)))]
                   (try
                     (wasm.api/initialize-viewport-with-alpha objects zoom bounds "#ffffff" alpha
                                                             (fn []
                                                               (wasm.api/render-sync-shape object-id)
                                                               (on-render)))
                     (catch :default cause
                       (reject cause))))))
              (p/fnly (fn [_ _] (wasm.api/clear-canvas)))))))))

(defn- register-export-api!
  []
  (set! (.-penpotExport js/globalThis)
        #js {:rasterize (fn [payload]
                          (let [request (parse-export-request payload)]
                            (render-with-rasterizer request)))
             :renderWasm (fn [payload]
                           (let [request (parse-export-request payload)]
                             (render-with-wasm request)))}))

(defn init-ui
  []
  (register-export-api!)
  (when-let [params (parse-params glob/location)]
    (when-let [component (case (:route params)
                           "objects"    (render-objects params)
                           "components" (render-components params)
                           nil)]
      (mf/render! app-root component))))

(defn ^:export init
  []
  (init-ui))

(defn reinit
  []
  (init-ui))

(defn ^:dev/after-load after-load
  []
  (reinit))
