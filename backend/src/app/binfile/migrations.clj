;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.binfile.migrations
  "A binfile related migrations handling"
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.features.components-v2 :as feat.compv2]
   [clojure.set :as set]
   [cuerdas.core :as str]))

(defn register-pending-migrations!
  "All features that are enabled and requires explicit migration are
  added to the state for a posterior migration step."
  [cfg {:keys [id features] :as file}]
  (doseq [feature (-> (::features cfg)
                      (set/difference cfeat/no-migration-features)
                      (set/difference cfeat/backend-only-features)
                      (set/difference features))]
    (vswap! bfc/*state* update :pending-to-migrate (fnil conj []) [feature id]))

  file)

(defn apply-pending-migrations!
  "Apply alredy registered pending migrations to files"
  [cfg]
  (doseq [[feature file-id] (-> bfc/*state* deref :pending-to-migrate)]
    (case feature
      "components/v2"
      (feat.compv2/migrate-file! cfg file-id
                                 :validate? (::validate cfg true)
                                 :skip-on-graphic-error? true)

      "fdata/shape-data-type"
      nil

      (ex/raise :type :internal
                :code :no-migration-defined
                :hint (str/ffmt "no migation for feature '%' on file importation" feature)
                :feature feature))))
