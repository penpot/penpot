;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.tooltip
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.tooltip.tooltip :as impl]))

(dm/export impl/tooltip*)
