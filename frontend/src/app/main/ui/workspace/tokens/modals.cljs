;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modals
  (:require
   [app.main.data.modal :as modal]
   [app.main.ui.workspace.tokens.modal :refer [tokens-properties-form]]
   [rumext.v2 :as mf]))

(mf/defc boolean-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/boolean}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc border-radius-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/border-radius}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc stroke-width-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/stroke-width}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc box-shadow-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/box-shadow}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc sizing-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/sizing}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc dimensions-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/dimensions}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc numeric-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/numeric}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc opacity-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/opacity}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc other-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/other}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc rotation-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/rotation}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc spacing-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/spacing}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc string-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/string}
  [properties]
  [:& tokens-properties-form properties])

(mf/defc typography-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/typography}
  [properties]
  [:& tokens-properties-form properties])
