;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.worker.thumbnails
  (:require
   [rumext.alpha :as mf]
   [cljs.spec.alpha :as s]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.main.exports :as exports]
   [uxbox.worker.impl :as impl]
   ["react-dom/server" :as rds]))

(mf/defc foobar
  [{:keys [name]}]
  [:span name])

(defmethod impl/handler :echo
  [message]
  {:result (rds/renderToString (mf/element foobar {:name "foobar"}))})

(defmethod impl/handler :thumbnails/generate
  [{:keys [data] :as message}]
  (let [elem (mf/element exports/page-svg #js {:data data
                                               :width "290"
                                               :height "150"})]
    (rds/renderToStaticMarkup elem)))

