;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.templates
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.storage :as storage]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def ^:private arrow-icon
  (i/icon-xref :arrow (stl/css :arrow-icon)))

(def ^:private download-icon
  (i/icon-xref :download (stl/css :download-icon)))

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
               (dcm/go-to-dashboard-recent
                :team-id team-id
                :project-id default-project-id))))]

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

(mf/defc title*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [on-click is-collapsed]}]
  (let [on-key-down
        (mf/use-fn
         (mf/deps on-click)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (on-click event))))]

    [:div {:class (stl/css :title)}
     [:button {:tab-index "0"
               :class (stl/css :title-btn)
               :on-click on-click
               :on-key-down on-key-down}
      [:span {:class (stl/css :title-text)}
       (tr "dashboard.libraries-and-templates")]
      (if ^boolean is-collapsed
        [:span {:class (stl/css :title-icon :title-icon-collapsed)}
         arrow-icon]
        [:span {:class (stl/css :title-icon)}
         arrow-icon])]]))

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

    [:a {:class (stl/css :card-container)
         :tab-index (if (or (not is-visible) collapsed) "-1" "0")
         :id id
         :data-index index
         :on-click on-click
         :on-mouse-down dom/prevent-default
         :on-key-down on-key-down}
     [:div {:class (stl/css :template-card)}
      [:div {:class (stl/css :img-container)}
       [:img {:src (dm/str thb)
              :alt (:name item)
              :loading "lazy"
              :decoding "async"}]]
      [:div {:class (stl/css :card-name)}
       [:span {:class (stl/css :card-text)} (:name item)]
       download-icon]]]))

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

    [:div {:class (stl/css :card-container)}
     [:div {:class (stl/css :template-card)}
      [:div {:class (stl/css :img-container)}
       [:a {:id id
            :tab-index (if (or (not is-visible) collapsed) "-1" "0")
            :href "https://penpot.app/libraries-templates"
            :target "_blank"
            :on-click on-click
            :on-key-down on-key-down}
        [:div {:class (stl/css :template-link)}
         [:div {:class (stl/css :template-link-title)} (tr "dashboard.libraries-and-templates")]
         [:div {:class (stl/css :template-link-text)} (tr "dashboard.libraries-and-templates.explore")]]]]]]))

(mf/defc templates-section*
  {::mf/props :obj}
  [{:keys [default-project-id profile project-id team-id]}]
  (let [templates      (mf/deref builtin-templates)
        templates      (mf/with-memo [templates]
                         (filterv #(and
                                    (not= (:id %) "welcome")
                                    (not= (:id %) "tutorial-for-beginners")) templates))

        route          (mf/deref refs/route)
        route-name     (get-in route [:data :name])
        section        (if (= route-name :dashboard-files)
                         (if (= project-id default-project-id)
                           "dashboard-drafts"
                           "dashboard-project")
                         (name route-name))

        collapsed*     (mf/use-state
                        #(get storage/global ::collapsed))
        collapsed      (deref collapsed*)



        can-move       (mf/use-state {:left false :right true})

        total          (count templates)

        ;; We need space for total plus the libraries&templates link
        content-ref    (mf/use-ref)

        move-left (fn [] (dom/scroll-by! (mf/ref-val content-ref) -300 0))
        move-right (fn [] (dom/scroll-by! (mf/ref-val content-ref) 300 0))

        on-toggle-collapse
        (mf/use-fn
         (fn [_event]
           (swap! collapsed* not)))

        on-scroll
        (mf/use-fn
         (fn [e]
           (let [scroll           (dom/get-target-scroll e)
                 scroll-left      (:scroll-left scroll)
                 scroll-available (- (:scroll-width scroll) scroll-left)
                 client-rect      (dom/get-client-size (dom/get-target e))
                 client-width     (unchecked-get client-rect "width")]

             (reset! can-move {:left (> scroll-left 0)
                               :right (> scroll-available client-width)}))))

        on-move-left
        (mf/use-fn #(move-left))

        on-move-left-key-down
        (mf/use-fn #(move-left))

        on-move-right
        (mf/use-fn #(move-right))

        on-move-right-key-down
        (mf/use-fn #(move-right))

        on-import-template
        (mf/use-fn
         (mf/deps default-project-id project-id section templates team-id)
         (fn [template _event]
           (import-template! template team-id project-id default-project-id section)))]

    (mf/with-effect [content-ref templates]
      (let [content (mf/ref-val content-ref)]
        (when (and (some? content) (some? templates))
          (dom/scroll-to content #js {:behavior "instant" :left 0 :top 0})
          (dom/dispatch-event content (dom/event "scroll")))))

    (mf/with-effect [profile collapsed]
      (swap! storage/global assoc ::collapsed collapsed)

      (when (and profile (not collapsed))
        (st/emit! (dd/fetch-builtin-templates))))

    [:div {:class (stl/css-case :dashboard-templates-section true
                                :collapsed collapsed)}
     [:> title* {:on-click on-toggle-collapse
                 :is-collapsed collapsed}]

     [:div {:class (stl/css :content)
            :on-scroll on-scroll
            :ref content-ref}

      (for [index (range (count templates))]
        [:& card-item
         {:on-import on-import-template
          :item (nth templates index)
          :index index
          :key index
          :is-visible true
          :collapsed collapsed}])

      [:& card-item-link
       {:is-visible true
        :collapsed collapsed
        :section section
        :total total}]]

     (when (:left @can-move)
       [:button {:class (stl/css :move-button :move-left)
                 :tab-index (if ^boolean collapsed "-1" "0")
                 :on-click on-move-left
                 :on-key-down on-move-left-key-down}
        arrow-icon])

     (when (:right @can-move)
       [:button {:class (stl/css :move-button :move-right)
                 :tab-index (if collapsed "-1" "0")
                 :on-click on-move-right
                 :aria-label (tr "labels.next")
                 :on-key-down  on-move-right-key-down}
        arrow-icon])]))
