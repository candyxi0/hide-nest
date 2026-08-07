import * as Dialog from "@radix-ui/react-dialog";
import * as Popover from "@radix-ui/react-popover";

/** Verifies Radix primitives import and type-check. Not rendered in production scaffold. */
export function RadixSmoke() {
  return (
    <>
      <Dialog.Root open={false} onOpenChange={() => {}}>
        <Dialog.Trigger />
        <Dialog.Portal>
          <Dialog.Overlay />
          <Dialog.Content />
        </Dialog.Portal>
      </Dialog.Root>
      <Popover.Root>
        <Popover.Trigger />
        <Popover.Portal>
          <Popover.Content />
        </Popover.Portal>
      </Popover.Root>
    </>
  );
}
