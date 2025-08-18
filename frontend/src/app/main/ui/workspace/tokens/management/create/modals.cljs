;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.modals
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon  :as i]
   [app.main.ui.workspace.tokens.management.create.form :refer [form-wrapper*]]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Component -------------------------------------------------------------------

(defn calculate-position
  "Calculates the style properties for the given coordinates and position"
  [{vh :height} position x y color?]
  (let [;; picker height in pixels
        ;; TODO: Revisit these harcoded values
        h (if color? 610 510)
        ;; Checks for overflow outside the viewport height
        max-y             (- vh h)
        overflow-fix      (max 0 (+ y (- 50) h (- vh)))
        bottom-offset     "1rem"
        top-offset        (dm/str (- y 70) "px")
        max-height-top    (str "calc(100vh - " top-offset)
        max-height-bottom (str "calc(100vh -" bottom-offset)
        x-pos 325
        rulers?       (mf/deref refs/rulers?)
        left-offset   (if rulers? 80 58)
        left-position (dm/str (- x x-pos) "px")]
    (cond
      (or (nil? x) (nil? y))
      {:left "auto" :right "16rem" :top "4rem"}

      (= position :left)
      (if (> y max-y)
        {:left left-position
         :bottom bottom-offset
         :maxHeight max-height-bottom}

        {:left left-position
         :maxHeight max-height-top
         :top (dm/str (- y 50 overflow-fix) "px")})

      :else
      (if (> y max-y)
        {:left (dm/str (+ x left-offset) "px")
         :bottom bottom-offset
         :maxHeight max-height-bottom}

        {:left (dm/str (+ x left-offset) "px")
         :top (dm/str (- y 70 overflow-fix) "px")
         :maxHeight max-height-top}))))

(defn use-viewport-position-style [x y position color?]
  (let [vport (-> (l/derived :vport refs/workspace-local)
                  (mf/deref))]
    (-> (calculate-position vport position x y color?)
        (clj->js))))

(mf/defc token-update-create-modal
  {::mf/wrap-props false}
  [{:keys [x y position token token-type action selected-token-set-name] :as _args}]
  (let [wrapper-style (use-viewport-position-style x y position (= token-type :color))
        modal-size-large* (mf/use-state (= token-type :typography))
        modal-size-large? (deref modal-size-large*)
        close-modal (mf/use-fn
                     (fn []
                       (modal/hide!)))
        update-modal-size (mf/use-fn
                           (fn [visible]
                             (reset! modal-size-large* visible)))]
    [:div {:class (stl/css-case
                   :token-modal-wrapper true
                   :token-modal-large modal-size-large?)
           :style wrapper-style
           :data-testid "token-update-create-modal"}
     [:> icon-button* {:on-click close-modal
                       :class (stl/css :close-btn)
                       :icon i/close
                       :variant "action"
                       :aria-label (tr "labels.close")}]
     [:> form-wrapper* {:token token
                        :action action
                        :selected-token-set-name selected-token-set-name
                        :token-type token-type
                        :on-display-colorpicker update-modal-size}]]))

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

(mf/defc number-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/number}
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

(mf/defc font-size-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/font-size}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc letter-spacing-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/letter-spacing}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc font-familiy-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/font-family}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc text-case-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/text-case}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc text-decoration-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/text-decoration}
  [properties]
  [:& token-update-create-modal properties])

(mf/defc font-weight-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/font-weight}
  [properties]
  [:& token-update-create-modal properties])
