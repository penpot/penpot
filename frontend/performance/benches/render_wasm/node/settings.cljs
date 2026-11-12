;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.settings
  "Shared operational settings and guidance.")

(def default-build-timeout-ms 300000)
(def default-startup-timeout-ms 30000)

(def gpu-guidance
  "Start the container with ./manage.sh run-devenv --gpu and use pnpm run render-bench.")
