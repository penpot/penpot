;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.foundations.assets.raw-svg
  (:refer-clojure :exclude [mask])
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.ui.ds.foundations.assets.raw-svg :refer [collect-raw-svgs]])
  (:require
   [rumext.v2 :as mf]))

(def ^:svg-id brand-openid "brand-openid")
(def ^:svg-id brand-github "brand-github")
(def ^:svg-id brand-gitlab "brand-gitlab")
(def ^:svg-id brand-google "brand-google")
(def ^:svg-id loader "loader")
(def ^:svg-id logo-error-screen "logo-error-screen")
(def ^:svg-id login-illustration "login-illustration")
(def ^:svg-id logo-subscription "logo-subscription")
(def ^:svg-id marketing-arrows "marketing-arrows")
(def ^:svg-id marketing-exchange "marketing-exchange")
(def ^:svg-id marketing-file "marketing-file")
(def ^:svg-id marketing-layers "marketing-layers")
(def ^:svg-id penpot-logo "penpot-logo")
(def ^:svg-id penpot-logo-icon "penpot-logo-icon")
(def ^:svg-id empty-placeholder-1-left "empty-placeholder-1-left")
(def ^:svg-id empty-placeholder-1-right "empty-placeholder-1-right")
(def ^:svg-id empty-placeholder-2-left "empty-placeholder-2-left")
(def ^:svg-id empty-placeholder-2-right "empty-placeholder-2-right")

(def raw-svg-list "A collection of all raw SVG assets" (collect-raw-svgs))

(mf/defc raw-svg*
  [{:keys [id] :rest props}]
  (assert (contains? raw-svg-list id) "invalid raw svg id")
  [:> "svg" props
   [:use {:href (dm/str "#asset-" id)}]])
