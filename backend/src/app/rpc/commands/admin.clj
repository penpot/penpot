;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.admin
  "Commands for instance superusers.

  This namespace is intentionally empty for now: it is the home for future
  superuser-guarded commands, which are served through the main API like
  every other command namespace. Access control lives in
  `wrap-authentication` (the `\"superuser\"` permission), not here, so a
  new command cannot forget it.")
