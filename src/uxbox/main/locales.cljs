;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.locales
  "Initialization namespace for i18n locale data."
  (:require [uxbox.util.i18n :as i18n]
            [uxbox.main.locales.en :as en]))

(defn init
  []
  (vswap! i18n/locales assoc :en en/locales))
