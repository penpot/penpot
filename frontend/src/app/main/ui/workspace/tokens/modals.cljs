;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modals
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon  :as i]
   [app.main.ui.workspace.tokens.form :refer [form]]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Component -------------------------------------------------------------------

(defn calculate-position
  "Calculates the style properties for the given coordinates and position"
  [{vh :height} position x y]
  (let [;; picker height in pixels
        h 510
        ;; Checks for overflow outside the viewport height
        overflow-fix (max 0 (+ y (- 50) h (- vh)))

        x-pos 325]
    (cond
      (or (nil? x) (nil? y)) {:left "auto" :right "16rem" :top "4rem"}
      (= position :left) {:left (str (- x x-pos) "px")
                          :top (str (- y 50 overflow-fix) "px")}
      :else {:left (str (+ x 80) "px")
             :top (str (- y 70 overflow-fix) "px")})))

(defn use-viewport-position-style [x y position]
  (let [vport (-> (l/derived :vport refs/workspace-local)
                  (mf/deref))]
    (-> (calculate-position vport position x y)
        (clj->js))))

(mf/defc token-update-create-modal
  {::mf/wrap-props false}
  [{:keys [x y position token token-type action selected-token-set-id] :as _args}]
  (let [wrapper-style (use-viewport-position-style x y position)
        close-modal (mf/use-fn
                     (fn []
                       (modal/hide!)))]
    [:div {:class (stl/css :token-modal-wrapper)
           :style wrapper-style}
     [:> icon-button* {:on-click close-modal
                       :class (stl/css :close-btn)
                       :icon i/close
                       :variant "action"
                       :aria-label (tr "labels.close")}]
     [:& form {:token token
               :action action
               :selected-token-set-id selected-token-set-id
               :token-type token-type}]]))

;; Modals ----------------------------------------------------------------------

(mf/defc boolean-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/boolean}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc border-radius-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/border-radius}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc color-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/color}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc stroke-width-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/stroke-width}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc box-shadow-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/box-shadow}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc sizing-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/sizing}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc dimensions-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/dimensions}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc numeric-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/numeric}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc opacity-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/opacity}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc other-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/other}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc rotation-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/rotation}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc spacing-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/spacing}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc string-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/string}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc typography-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/typography}
  [properties]
  [:& token-update-create-modal properties])
