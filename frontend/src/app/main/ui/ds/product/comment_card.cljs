;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.comment-card
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.review-label :refer [review-label*]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(def ^:private schema:comment-card
  [:map
   [:class {:optional true} [:maybe :string]]
   [:location :string]
   [:author :string]
   [:time :string]
   [:replies :string]
   [:avatar {:optional true} [:maybe :any]]
   [:anchor {:optional true} [:maybe :any]]
   [:review {:optional true} [:maybe :string]]
   [:is-review {:optional true} [:maybe :boolean]]
   [:is-open {:optional true} [:maybe :boolean]]
   [:is-resolved {:optional true} [:maybe :boolean]]
   [:on-click {:optional true} [:maybe fn?]]])

(mf/defc comment-card*
  "One comment of a list: where it is, who wrote it and when (`avatar`, an
  avatar), its text (`children`), and its replies beside what it points at
  (`anchor`, a chip). The whole card opens it. `review` names the design review
  it belongs to, and `is-review` outlines it open in the review colour."
  {::mf/schema schema:comment-card}
  [{:keys [class location author time replies avatar anchor review is-review is-open is-resolved
           on-click children]
    :rest props}]
  (let [on-key-down (mf/use-fn
                     (mf/deps on-click)
                     (fn [event]
                       ;; the text may hold links: the keys on them are theirs
                       (when (and on-click
                                  (identical? (dom/get-target event) (dom/get-current-target event))
                                  (or (kbd/enter? event) (kbd/space? event)))
                         (dom/prevent-default event)
                         (on-click event))))
        props       (mf/spread-props props
                                     {:class [class (stl/css-case :comment-card true
                                                                  :is-open is-open
                                                                  :is-review is-review
                                                                  :is-resolved is-resolved)]
                                      :role "button"
                                      :tab-index 0
                                      :on-click on-click
                                      :on-key-down on-key-down})]
    [:> :div props
     [:> text* {:as "span" :typography t/body-small :class (stl/css :location)} location]
     (when review
       [:> review-label* {:label review}])
     [:div {:class (stl/css :author)}
      avatar
      [:div {:class (stl/css :identity)}
       [:> text* {:as "span" :typography t/body-small :class (stl/css :name)} author]
       [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)} time]]]
     [:div {:class (stl/css :content)} children]
     [:div {:class (stl/css :footer)}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :replies)} replies]
      anchor]]))
