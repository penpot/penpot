;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.foundations.raw-svg
  (:refer-clojure :exclude [mask])
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.ui.ds.foundations.raw-svg :refer [collect-raw-svgs]])
  (:require
   [rumext.v2 :as mf]))

(def ^:svg-id brand-openid "brand-openid")
(def ^:svg-id brand-github "brand-github")
(def ^:svg-id brand-gitlab "brand-gitlab")
(def ^:svg-id brand-google "brand-google")

(def ^:svg-id loader "loader")
(def ^:svg-id logo "penpot-logo")
(def ^:svg-id logo-icon "penpot-logo-icon")
(def ^:svg-id logo-error-screen "logo-error-screen")
(def ^:svg-id login-illustration "login-illustration")

(def ^:svg-id v2-icon-1 "v2-icon-1")
(def ^:svg-id v2-icon-2 "v2-icon-2")
(def ^:svg-id v2-icon-3 "v2-icon-3")
(def ^:svg-id v2-icon-4 "v2-icon-4")

(def raw-svg-list "A collection of all raw SVG assets" (collect-raw-svgs))

(mf/defc raw-svg*
  {::mf/props :obj}
  [{:keys [asset] :rest props}]
  [:> "svg" props
   [:use {:href (dm/str "#asset-" asset)}]])
