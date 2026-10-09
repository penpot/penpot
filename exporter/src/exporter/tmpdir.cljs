;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.tmpdir
  "The temp-file area as a system component (`:exporter/tmpdir`).

  Its config value names the directory (`{:path ...}`, read from the
  environment at the wiring layer); booting ensures it exists and the
  running instance is the path itself, which renderers and runners
  read straight from their config. Fail fast on a blank path instead
  of failing the first render that writes. Named for what it owns,
  not for the legacy namespace it shadows."
  (:require
   [exporter.shell :as shell]
   [exporter.utils.system :as system]))

(defmethod system/init-key :exporter/tmpdir
  [_ {:keys [path]}]
  (shell/ensure-dir path))
