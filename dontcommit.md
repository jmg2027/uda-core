1. Accept
2. Accept. Or maybe PacketSlotizeUnit? SlotSliceUnit? 
3. PublishMux was originally combines results and arbitrates that write it to gpr or vgpr based on information from commit unit. It looks like this node does not have a role for data forward based on original concept...
4. There is no specific function for commitunit or renamer, so it can be flexible. Just keep in mind core concept of this repository
5. This is not typical ooo, so there is no guarantee of register slicing in data flow edges. This should not affect functionality. So, it is possible that 
Cycle 0: Fetch
Cycle 1: Decode, Rename, RS wakeup/issue, FU execute, Commit
and this is much more general operation. Pipelining or register slicing should not change function and should be done only for critical timing issue after PI/PD. This is why all interfaces are ready/valid. 
6. Correct. Well, there is no stall logic or pipeline registers in node, because I belive edge should have responsible for them. Stall logic -> Ready backpropagation, pipeline register -> edge timing property