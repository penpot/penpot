// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";
import styles from "./HintMessage.module.scss";

export type HintMessageType = "hint" | "warning" | "error";

export interface HintMessageProps extends ComponentPropsWithRef<"div"> {
  /** Unique id – the inner span gets id `${id}-hint` */
  id: string;
  /** The message to display */
  message?: string;
  /** Visual/semantic type of the hint */
  type?: HintMessageType;
}

function HintMessageInner({
  id,
  message,
  type = "hint",
  className,
  ...rest
}: HintMessageProps) {
  const ariaLive =
    type === "warning" || type === "error" ? "polite" : undefined;

  return (
    <div
      className={clsx(
        styles["hint-message"],
        styles[`type-${type}`],
        className,
      )}
      aria-live={ariaLive}
      {...rest}
    >
      {message != null && (
        <span className={styles["hint-message-text"]} id={`${id}-hint`}>
          {message}
        </span>
      )}
    </div>
  );
}

export const HintMessage = memo(HintMessageInner);
