`timescale 1ns/1ps
module SramModelCheck;
  reg clock = 0;
  reg csb0 = 1, web0 = 1, csb1 = 1;
  reg [3:0] mask = 0;
  reg [7:0] addr0 = 0, addr1 = 0;
  reg [31:0] din = 0;
  wire [31:0] vendor0, vendor1, cycle0, cycle1;
  reg [31:0] expected [0:255];
  integer checks = 0, cycles = 0;
  reg [31:0] captured_vendor0, captured_vendor1, captured_cycle0, captured_cycle1;
  reg previous_read0 = 0, previous_read1 = 0;
  reg [31:0] previous_expected0, previous_expected1;
  always @(posedge clock) begin
    captured_vendor0 <= vendor0; captured_cycle0 <= cycle0;
    captured_vendor1 <= vendor1; captured_cycle1 <= cycle1;
  end
  sky130_sram_1kbyte_1rw1r_32x256_8 #(.VERBOSE(0)) vendor(
    .clk0(clock), .csb0(csb0), .web0(web0), .wmask0(mask), .addr0(addr0),
    .din0(din), .dout0(vendor0), .clk1(clock), .csb1(csb1), .addr1(addr1), .dout1(vendor1));
  uda_sram_cycle_model cycle_model(
    .clk0(clock), .csb0(csb0), .web0(web0), .wmask0(mask), .addr0(addr0),
    .din0(din), .dout0(cycle0), .clk1(clock), .csb1(csb1), .addr1(addr1), .dout1(cycle1));

  task automatic step(input integer op0, input [7:0] wa, input [31:0] wd,
                       input [3:0] wm, input reg read1, input [7:0] ra,
                       input reg perturb);
    integer b;
    begin
      if (op0 == 2 && read1 && wa == ra) $fatal(1, "undefined read/write collision in stimulus");
      csb0 = (op0 == 0); web0 = (op0 != 2); addr0 = wa; din = wd; mask = wm;
      csb1 = !read1; addr1 = ra;
      #5 clock = 1;
      #2;
      if (previous_read0) begin
        if (captured_vendor0 !== captured_cycle0 || captured_vendor0 !== previous_expected0)
          $fatal(1, "port0 same-edge capture mismatch cycle=%0d", cycles);
        checks = checks + 1;
      end
      if (previous_read1) begin
        if (captured_vendor1 !== captured_cycle1 || captured_vendor1 !== previous_expected1)
          $fatal(1, "port1 same-edge capture mismatch cycle=%0d", cycles);
        checks = checks + 1;
      end
      if (perturb) begin
        csb0 = !csb0; web0 = !web0; addr0 = ~wa; din = ~wd; mask = ~wm;
        csb1 = !csb1; addr1 = ~ra;
      end
      #3 clock = 0;
      if (op0 == 2)
        for (b=0; b<4; b=b+1)
          if (wm[b]) expected[wa][b*8 +: 8] = wd[b*8 +: 8];
      #4;
      if (op0 == 1) begin
        if (vendor0 !== cycle0 || vendor0 !== expected[wa])
          $fatal(1, "port0 mismatch cycle=%0d row=%0d vendor=%h cycle=%h expected=%h", cycles,wa,vendor0,cycle0,expected[wa]);
        checks = checks + 1;
      end
      if (read1) begin
        if (vendor1 !== cycle1 || vendor1 !== expected[ra])
          $fatal(1, "port1 mismatch cycle=%0d row=%0d vendor=%h cycle=%h expected=%h", cycles,ra,vendor1,cycle1,expected[ra]);
        checks = checks + 1;
      end
      previous_read0 = (op0 == 1); previous_read1 = read1;
      previous_expected0 = expected[wa]; previous_expected1 = expected[ra];
      #1;
      cycles = cycles + 1;
    end
  endtask

  integer a, m;
  initial begin
    for (a=0;a<256;a=a+1) begin
      expected[a] = 0;
      step(2,a[7:0],32'h12345678 ^ (a * 32'h1020304),4'hf,0,0,a[0]);
    end
    for (a=0;a<256;a=a+1)
      step(1,a[7:0],0,0,1,~a[7:0],a[0]);
    for (m=0;m<16;m=m+1)
      for (a=0;a<256;a=a+1) begin
        step(2,a[7:0],32'hA5F01234 ^ (a * 32'h03020100) ^ (m * 32'h11111111),m[3:0],1,a[7:0]+8'd1,a[0]);
        step(1,a[7:0],0,0,1,a[7:0],!a[0]);
      end
    for (a=0;a<256;a=a+1) begin
      step(0,a[7:0],32'hffffffff,4'hf,0,0,1);
      step(0,0,0,0,1,a[7:0],0);
    end
    step(0,0,0,0,0,0,0);
    $display("SRAM_MODEL_CHECK_PASS cycles=%0d read_checks=%0d", cycles, checks);
    $finish;
  end
endmodule
