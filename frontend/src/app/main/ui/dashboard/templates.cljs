;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.templates
  (:require
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.config :as cf]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(def builtin-templates
  (l/derived :builtin-templates st/state))

(defn- import-template!
  [template team-id project-id default-project-id section]
  (letfn [(on-finish []
            (st/emit!
             (ptk/event ::ev/event {::ev/name "import-template-finish"
                                    ::ev/origin "dashboard"
                                    :template (:name template)
                                    :section section})

             (when-not (some? project-id)
               (rt/nav :dashboard-files
                       {:team-id team-id
                        :project-id default-project-id}))))]

    (st/emit!
     (ptk/event ::ev/event {::ev/name "import-template-launch"
                            ::ev/origin "dashboard"
                            :template (:name template)
                            :section section})

     (modal/show
      {:type :import
       :project-id (or project-id default-project-id)
       :files []
       :template template
       :on-finish-import on-finish}))))

(mf/defc title
  {::mf/wrap-props false}
  [{:keys [collapsed]}]
  (let [on-click
        (mf/use-fn
         (mf/deps collapsed)
         (fn [_event]
           (let [props {:builtin-templates-collapsed-status (not collapsed)}]
             (st/emit! (du/update-profile-props props)))))

        on-key-down
        (mf/use-fn
         (mf/deps on-click)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (on-click event))))]

    [:div.title
     [:button {:tab-index "0"
               :on-click on-click
               :on-key-down on-key-down}
      [:span (tr "dashboard.libraries-and-templates")]
      [:span.icon (if ^boolean collapsed i/arrow-up i/arrow-down)]]]))

(mf/defc card-item
  {::mf/wrap-props false}
  [{:keys [item index is-visible collapsed on-import]}]
  (let [id  (dm/str "card-container-" index)
        thb (assoc cf/public-uri :path (dm/str "/images/thumbnails/template-" (:id item) ".jpg"))

        on-click
        (mf/use-fn
         (mf/deps on-import)
         (fn [event]
           (on-import item event)))

        on-key-down
        (mf/use-fn
         (mf/deps on-import)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (on-import item event))))]

    [:a.card-container
     {:tab-index (if (or (not is-visible) collapsed) "-1" "0")
      :id id
      :data-index index
      :on-click on-click
      :on-key-down on-key-down}
     [:div.template-card
      [:div.img-container
       [:img {:src (dm/str thb)
              :alt (:name item)}]]
      [:div.card-name [:span (:name item)]
        [:span.icon i/download]]]]))

(mf/defc card-item-link
  {::mf/wrap-props false}
  [{:keys [total is-visible collapsed section]}]
  (let [id (dm/str "card-container-" total)

        on-click
        (mf/use-fn
         (mf/deps section)
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-libraries-click"
                                            ::ev/origin "dashboard"
                                            :section section}))))

        on-key-down
        (mf/use-fn
         (mf/deps on-click)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (on-click event))))]

    [:div.card-container
     [:div.template-card
      [:div.img-container
       [:a {:id id
            :tab-index (if (or (not is-visible) collapsed) "-1" "0")
            :href "https://penpot.app/libraries-templates.html"
            :target "_blank"
            :on-click on-click
            :on-key-down on-key-down}
        [:div.template-link
         [:div.template-link-title (tr "dashboard.libraries-and-templates")]
         [:div.template-link-text (tr "dashboard.libraries-and-templates.explore")]]]]]]))

(mf/defc templates-section
  {::mf/wrap-props false}
  [{:keys [default-project-id profile project-id team-id content-width]}]
  (let [templates      (->>  (mf/deref builtin-templates)
                             (filter #(not= (:id %) "tutorial-for-beginners")))

        route          (mf/deref refs/route)
        route-name     (get-in route [:data :name])
        section        (if (= route-name :dashboard-files)
                         (if (= project-id default-project-id)
                           "dashboard-drafts"
                           "dashboard-project")
                         (name route-name))

        props          (:props profile)
        collapsed      (:builtin-templates-collapsed-status props false)
        card-offset*   (mf/use-state 0)
        card-offset    (deref card-offset*)

        card-width     275
        total          (count templates)
        container-size (* (+ 2 total) card-width)

        ;; We need space for total plus the libraries&templates link
        more-cards     (> (+ card-offset (* (+ 1 total) card-width)) content-width)
        card-count     (mth/floor (/ content-width 275))
        left-moves     (/ card-offset -275)
        first-card     left-moves
        last-card      (+ (- card-count 1) left-moves)
        content-ref    (mf/use-ref)

        on-move-left
        (mf/use-fn
         (mf/deps card-offset card-width)
         (fn [_event]
           (when-not (zero? card-offset)
             (dom/animate! (mf/ref-val content-ref)
                           [#js {:left (dm/str card-offset "px")}
                            #js {:left (dm/str (+ card-offset card-width) "px")}]
                           #js {:duration 200 :easing "linear"})
             (reset! card-offset* (+ card-offset card-width)))))

        on-move-left-key-down
        (mf/use-fn
         (mf/deps on-move-left first-card)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (on-move-left event)
             (when-let [node (dom/get-element (dm/str "card-container-" first-card))]
               (dom/focus! node)))))

        on-move-right
        (mf/use-fn
         (mf/deps more-cards card-offset card-width)
         (fn [_event]
           (when more-cards
             (swap! card-offset* inc)
             (dom/animate! (mf/ref-val content-ref)
                           [#js {:left (dm/str card-offset "px")}
                            #js {:left (dm/str (- card-offset card-width) "px")}]
                           #js {:duration 200 :easing "linear"})
             (reset! card-offset* (- card-offset card-width)))))

        on-move-right-key-down
        (mf/use-fn
         (mf/deps on-move-right last-card)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (on-move-right event)
             (when-let [node (dom/get-element (dm/str "card-container-" last-card))]
               (dom/focus! node)))))

        on-import-template
        (mf/use-fn
         (mf/deps default-project-id project-id section templates team-id)
         (fn [template _event]
           (import-template! template team-id project-id default-project-id section)))

        ]

    (mf/with-effect [collapsed]
      (when-not collapsed
        (st/emit! (dd/fetch-builtin-templates))))

    [:div.dashboard-templates-section
     {:class (when ^boolean collapsed "collapsed")}
     [:& title {:collapsed collapsed}]

     [:div.content {:ref content-ref
                    :style {:left card-offset
                            :width (dm/str container-size "px")}}

      (for [index (range (count templates))]
        [:& card-item
         {:on-import on-import-template
          :item (nth templates index)
          :index index
          :key index
          :is-visible (and (>= index first-card)
                           (<= index last-card))
          :collapsed collapsed}])

      [:& card-item-link
       {:is-visible (and (>= total first-card) (<= total last-card))
        :collapsed collapsed
        :section section
        :total total}]]

     (when (< card-offset 0)
       [:button.button.left
        {:tab-index (if ^boolean collapsed "-1" "0")
         :on-click on-move-left
         :on-key-down on-move-left-key-down}
        i/go-prev])

     (when more-cards
       [:button.button.right
        {:tab-index (if collapsed "-1" "0")
         :on-click on-move-right
         :aria-label (tr "labels.next")
         :on-key-down  on-move-right-key-down}
        i/go-next])]))

