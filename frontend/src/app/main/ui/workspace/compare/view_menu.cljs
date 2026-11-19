;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.compare.view-menu
  "The control of the compare toolbar that says how the canvas shows the
  changes, and the menu that picks another way or hides them."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.compare :as dwcp]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.menu :refer [menu* menu-item* menu-separator* sub-menu*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- on-action
  [action]
  (case action
    "highlight"    (st/emit! (dwcp/set-view :highlight))
    "side-linked"  (st/emit! (dwcp/set-view :side) (dwcp/set-side-mode :linked))
    "side-aligned" (st/emit! (dwcp/set-view :side) (dwcp/set-side-mode :aligned))
    "hide"         (st/emit! (dwcp/toggle-hidden))
    nil))

(mf/defc view-item*
  "The label of a view in the menu, ticked when it is the one in use."
  {::mf/private true}
  [{:keys [label is-selected]}]
  [:span {:class (stl/css-case :item true :is-selected is-selected)}
   [:span {:class (stl/css :item-label)} label]
   (when is-selected
     [:> i/icon* {:icon-id i/tick :size "s"}])])

(mf/defc view-mode-menu*
  "`view` is the view in use, `side-mode` how the side by side view
  places main and `hidden` says the changes are hidden. Inside a design
  review (`is-review`) the control takes the colour of the review."
  [{:keys [view side-mode hidden is-review]}]
  (let [open*     (mf/use-state false)
        open?     (deref open*)
        on-toggle (mf/use-fn #(swap! open* not))
        on-change (mf/use-fn #(reset! open* %))
        ;; main beside the branch is drawn by the WASM render alone
        wasm?     (features/use-feature "render-wasm/v1")
        side?     (and wasm? (= view :side) (not hidden))
        aligned?  (= side-mode :aligned)
        highlight (tr "workspace.compare.view.highlight")
        side      (tr "workspace.branches.review.view.side")
        linked    (tr "workspace.compare.view.side.linked")
        lined-up  (tr "workspace.compare.view.side.aligned")
        ;; the mode of the side view is told by the note on the canvas
        current   (if (and wasm? (= view :side)) side highlight)]
    [:> menu* {:is-open open?
               :on-open-change on-change
               :on-action on-action
               :placement "bottom start"
               :trigger
               (mf/html
                [:button {:class (stl/css-case :trigger true
                                               :is-hidden hidden
                                               :is-review is-review)
                          :aria-label (tr "workspace.compare.view.label")
                          :aria-expanded open?
                          :on-click on-toggle}
                 [:> i/icon* {:icon-id (if hidden i/hide i/shown) :size "s"}]
                 [:> text* {:as "span" :typography t/body-small}
                  (if hidden
                    (tr "workspace.compare.view.hidden")
                    current)]
                 [:> i/icon* {:icon-id i/arrow-down :size "s"}]])}
     [:> menu-item* {:id "highlight" :text-value highlight}
      [:> view-item* {:label highlight
                      :is-selected (and (= view :highlight) (not hidden))}]]
     ;; the two ways main stands beside the branch
     [:> sub-menu* {:id "side"
                    :text-value side
                    :trigger side
                    :is-disabled (not wasm?)
                    :on-action on-action}
      [:> menu-item* {:id "side-linked" :text-value linked}
       [:> view-item* {:label linked
                       :is-selected (and side? (not aligned?))}]]
      [:> menu-item* {:id "side-aligned" :text-value lined-up}
       [:> view-item* {:label lined-up
                       :is-selected (and side? aligned?)}]]]
     [:> menu-separator* {}]
     [:> menu-item* {:id "hide"}
      (if hidden
        (tr "workspace.compare.view.show")
        (tr "workspace.compare.view.hide"))]]))
