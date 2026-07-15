import {
  DialogTrigger,
  ModalOverlay,
  Modal as RACModal,
  Dialog,
} from 'react-aria-components';
import {
  createContext,
  useContext,
  type ReactNode,
} from 'react';
import styles from './Modal.module.scss';

const ModalCloseContext = createContext<(() => void) | null>(null);

export function useModalClose(): (() => void) | null {
  return useContext(ModalCloseContext);
}

interface ModalProps {
  isOpen?: boolean;
  onOpenChange?: (isOpen: boolean) => void;
  children: ReactNode;
  trigger?: ReactNode;
  isDismissable?: boolean;
  size?: 'small' | 'medium' | 'large' | 'xlarge';
  className?: string;
}

export function Modal({
  isOpen,
  onOpenChange,
  children,
  trigger,
  isDismissable = true,
  size = 'medium',
  className,
}: ModalProps) {
  const sizeClass =
    size === 'small'
      ? styles.modalSmall
      : size === 'large'
        ? styles.modalLarge
        : size === 'xlarge'
          ? styles.modalXlarge
          : styles.modalMedium;

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
                {children}
              </ModalCloseContext.Provider>
            )}
          </Dialog>
        </RACModal>
      </ModalOverlay>
    </DialogTrigger>
  );
}
