package com.att.aro.core.packetreader.impl;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;

import org.junit.Test;
import org.junit.Before;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;

import com.att.aro.core.BaseTest;
import com.att.aro.core.packetreader.IPacketService;
import com.att.aro.core.packetreader.IPcapngHelper;
import com.att.aro.core.packetreader.pojo.IPPacket;
import com.att.aro.core.packetreader.pojo.Packet;
import com.att.aro.core.packetreader.pojo.TCPPacket;
import com.att.aro.core.packetreader.pojo.UDPPacket;

public class PacketServiceImplTest extends BaseTest {

	IPcapngHelper helper;
	PacketServiceImpl service;
	File file;
	@Before
	public void setup() throws FileNotFoundException{
		service = (PacketServiceImpl) context.getBean(IPacketService.class);

		helper = Mockito.mock(IPcapngHelper.class);
		Mockito.when(helper.isApplePcapng(Mockito.any(File.class))).thenReturn(true);
		
		file = Mockito.mock(File.class);
		Mockito.when(file.getAbsolutePath()).thenReturn("test");
		
	}	
	@Test
	public void createPacketFromPcapTest(){
//		ReflectionTestUtils.setField(service, "pcapngHelper", helper);
		byte[] data = new byte[64];
		data[0] = 0;
		data[1] = 0;
		data[2] = 0;
		data[3] = 0;
		data[4] = 12;
		data[5] = 15;

		try {
			Mockito.when(helper.isApplePcapng(Mockito.any(File.class))).thenReturn(true);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		service.createPacketFromPcap(13, 1, 1, 1, data, "abcde");
		
		
	}
	
	@Test
	public void createPacketTest(){
		service = (PacketServiceImpl) context.getBean(IPacketService.class);
		ReflectionTestUtils.setField(service, "pcapngHelper", helper);
		byte[] data = new byte[64];
		data[0] = 0;
		data[1] = 0;
		data[2] = 0;
		data[3] = 0;
		
		ByteBuffer buffer = ByteBuffer.allocate(2);
		short network = 0x0800;//IPV4
		buffer.putShort(network);
		buffer.flip();
		byte[] netarr = buffer.array();
		data[12] = netarr[0];
		data[13] = netarr[1];
		data[14] = netarr[0];
		data[15] = netarr[1];
		ByteBuffer datawrap = ByteBuffer.wrap(data);
		short value = datawrap.getShort(12);
		assertEquals(network, value);
		Packet packet = service.createPacketFromPcap(12, 1, 1, 1, data, null);
		packet = service.createPacketFromPcap(1, 1, 1, 1, data, null);
		packet = service.createPacketFromPcap(113, 1, 1, 1, data, null);
		
		packet = service.createPacketFromPcap(0, 1, 1, 1, data, file.getAbsolutePath());
		
		packet = service.createPacketFromNetmon(0xe000, 1, 1, 1, data);
		
		packet = service.createPacketFromNetmon(9, 1, 1, 1, data);
		packet = service.createPacketFromNetmon(8, 1, 1, 1, data);
		packet = service.createPacketFromNetmon(1, 1, 1, 1, data);
		
		packet = service.createPacketFromNetmon(6, 1, 1, 1, data);
		data[1] = 0x2;
		data[2] = 0x8;
		packet = service.createPacketFromNetmon(6, 1, 1, 1, data);
		
		//IPV6
		data[6] = 0x6;
		packet = service.createPacket((short) 0x86DD, 1, 1, 1, 0, data);
		boolean ok = false;
		if(packet instanceof TCPPacket){
			ok = true;
		}
		assertEquals(true, ok);
		
		data[6] = 0x11;
		packet = service.createPacket((short) 0x86DD, 1, 1, 1, 0, data);
		if(packet instanceof UDPPacket){
			ok = true;
		}
		assertEquals(true, ok);
		data[6] = 0x8;
		packet = service.createPacket((short) 0x86DD, 1, 1, 1, 0, data);
		
		//IPV4
		data[9] = 0x6;
		packet = service.createPacket((short) 0x0800, 1, 1, 1, 0, data);
		data[9] = 0x11;
		packet = service.createPacket((short) 0x0800, 1, 1, 1, 0, data);
		data[9] = 0x8;
		packet = service.createPacket((short) 0x0800, 1, 1, 1, 0, data);
		
		assertNotNull(packet);
	}
}
