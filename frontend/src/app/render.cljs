;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render
  "The main entry point for UI part needed by the exporter."
  (:require
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.logging :as log]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.types.components-list :as ctkl]
   [app.common.uri :as u]
   [app.main.data.fonts :as df]
   [app.main.features :as features]
   [app.main.render :as render]
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.globals :as glob]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [garden.core :refer [css]]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
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
  [{:keys [object-id embed]}]
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
      [:& render/object-svg
       {:objects objects
        :object-id object-id
        :embed embed}])))

(mf/defc objects-svg
  {::mf/wrap-props false}
  [{:keys [object-ids embed]}]
  (when-let [objects (mf/deref ref:objects)]
    (for [object-id object-ids]
      (let [objects (render/adapt-objects-for-shape objects object-id)]
        [:& render/object-svg
         {:objects objects
          :key (str object-id)
          :object-id object-id
          :embed embed}]))))

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
   [:object-id
    [:or
     ::sm/uuid
     ::sm/coll-of-uuid]]])

(def ^:private render-objects-decoder
  (sm/lazy-decoder schema:render-objects
                   sm/string-transformer))

(def ^:private render-objects-validator
  (sm/lazy-validator schema:render-objects))

(defn- render-objects
  [params]
  (let [{:keys [file-id page-id embed share-id object-id] :as params} (render-objects-decoder params)]
    (if-not (render-objects-validator params)
      (do
        (js/console.error "invalid arguments")
        (sm/pretty-explain schema:render-objects params)
        nil)

      (do
        (st/emit! (fetch-objects-bundle :file-id file-id :page-id page-id :share-id share-id :object-id object-id))

        (if (uuid? object-id)
          (mf/html
           [:& object-svg
            {:file-id file-id
             :page-id page-id
             :share-id share-id
             :object-id object-id
             :embed embed}])

          (mf/html
           [:& objects-svg
            {:file-id file-id
             :page-id page-id
             :share-id share-id
             :object-ids (into #{} object-id)
             :embed embed}]))))))

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

(def ^:private render-components-decoder
  (sm/lazy-decoder schema:render-components
                   sm/string-transformer))

(def ^:private render-components-validator
  (sm/lazy-validator schema:render-components))

(defn render-components
  [params]
  (let [{:keys [file-id component-id embed] :as params} (render-components-decoder params)]
    (if-not (render-components-validator params)
      (do
        (js/console.error "invalid arguments")
        (sm/pretty-explain schema:render-components params)
        nil)

      (do
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
           :embed embed}])))))

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

(defn init-ui
  []
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



