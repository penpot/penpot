import {
  DialogTrigger,
  ModalOverlay,
  Modal as RACModal,
  Dialog,
} from 'react-aria-components';
import {
  createContext,
  useContext,
  cloneElement,
  type ReactNode,
  type ReactElement,
  type ButtonHTMLAttributes,
} from 'react';
import styles from './Modal.module.scss';

const ModalCloseContext = createContext<(() => void) | null>(null);

function useModalClose(): (() => void) | null {
  return useContext(ModalCloseContext);
}

interface ModalProps {
  isOpen?: boolean;
  onOpenChange?: (isOpen: boolean) => void;
  children: ReactNode;
  trigger?: ReactNode;
  heading?: ReactNode;
  closeButton?: ReactElement;
  isDismissable?: boolean;
  size?: 'small' | 'medium' | 'large';
  className?: string;
}

export function Modal({
  isOpen,
  onOpenChange,
  children,
  trigger,
  heading,
  closeButton,
  isDismissable = true,
  size = 'medium',
  className,
}: ModalProps) {
  const sizeClass = size === 'small' ? styles.modalSmall : size === 'large' ? styles.modalLarge : styles.modalMedium;

  return (
    <DialogTrigger isOpen={isOpen} onOpenChange={onOpenChange}>
      {trigger}
      <ModalOverlay className={styles.overlay} isDismissable={isDismissable}>
        <RACModal
          className={`${styles.modal} ${sizeClass} ${className ?? ''}`}
        >
          <Dialog className={styles.dialog}>
            {({ close }) => (
              <ModalCloseContext.Provider value={close}>
                {(heading || closeButton) && (
                  <div className={styles.header}>
                    {heading}
                    {closeButton
                      ? cloneElement(closeButton as ReactElement<{ onClick: () => void }>, { onClick: close })
                      : null}
                  </div>
                )}
                <div className={styles.content}>
                  {children}
                </div>
              </ModalCloseContext.Provider>
            )}
          </Dialog>
        </RACModal>
      </ModalOverlay>
    </DialogTrigger>
  );
}

interface ModalCloseButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement> {
  children?: ReactNode;
}

export function ModalCloseButton({
  children,
  className,
  onClick,
  ...props
}: ModalCloseButtonProps) {
  const close = useModalClose();
  return (
    <button
      className={`${styles.closeButton} ${className ?? ''}`}
      onClick={(e) => { close?.(); onClick?.(e); }}
      {...props}
    >
      {children}
    </button>
  );
}
