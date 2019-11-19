package com.mmall.service.impl;

import com.alipay.api.AlipayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.demo.trade.config.Configs;
import com.alipay.demo.trade.model.ExtendParams;
import com.alipay.demo.trade.model.GoodsDetail;
import com.alipay.demo.trade.model.builder.AlipayTradePrecreateRequestBuilder;
import com.alipay.demo.trade.model.result.AlipayF2FPrecreateResult;
import com.alipay.demo.trade.service.AlipayTradeService;
import com.alipay.demo.trade.service.impl.AlipayTradeServiceImpl;
import com.alipay.demo.trade.utils.ZxingUtils;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mmall.common.Const;
import com.mmall.common.ServerResponse;
import com.mmall.dao.*;
import com.mmall.pojo.*;
import com.mmall.service.IOrderService;
import com.mmall.util.BigDecimalUtil;
import com.mmall.util.DateTimeUtil;
import com.mmall.util.FTPUtil;
import com.mmall.util.PropertiesUtil;
import com.mmall.vo.OrderItemVO;
import com.mmall.vo.OrderProductVO;
import com.mmall.vo.OrderVO;
import com.mmall.vo.ShippingVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.xml.crypto.Data;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/**
 * created by dy
 */
@Service("iOrderService")
public class OrderServiceImpl implements IOrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private PayInfoMapper payInfoMapper;
    @Autowired
    private CartMapper cartMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ShippingMapper shippingMapper;

    public ServerResponse createOrder(Integer userId, Integer shippingId){
        // 从数据库中获取数据
        List<Cart> cartList = cartMapper.selectCartByUserId(userId);
        ServerResponse serverResponse = this.getCartOrderItem(userId, cartList);
        if (!serverResponse.isSuccess()) {
            return serverResponse;
        }
        List<OrderItem> orderItemList = (List<OrderItem>) serverResponse.getData();
        BigDecimal payment = this.getTotalPrice(orderItemList);
        // 生成订单
        Order order = this.assembleOrder(userId, shippingId, payment);
        if (order == null) {
            return ServerResponse.createByErrorMessage("生成订单错误");
        }
        if (CollectionUtils.isEmpty(orderItemList)) {
            return ServerResponse.createByErrorMessage("购物车为空");
        }
        for (OrderItem orderItem : orderItemList) {
            orderItem.setOrderNo(order.getOrderNo());
        }
        // mybatis 批量插入
        orderItemMapper.batchInsert(orderItemList);

        // 生成成功，要减少产品的库存
        this.reduceProductStock(orderItemList);

        // 清空一下购物车
        this.cleanCart(cartList);

        // 返回给前端数据
        OrderVO orderVO = this.assembleOrderVO(order, orderItemList);
        return ServerResponse.createBySuccess(orderVO);

    }

    public ServerResponse<String> cancel(Integer userId, Long orderNo) {
        Order order = orderMapper.selectByUserIdAndOrderNo(userId, orderNo);
        if (order == null) {
            return ServerResponse.createByErrorMessage("该用户此订单不存在");
        }
        if (order.getStatus() != Const.OrderStatusEnum.NO_PAY.getCode()) {
            return ServerResponse.createByErrorMessage("订单已付款，无法取消");
        }
        Order updateOrder = new Order();
        updateOrder.setId(order.getId());
        updateOrder.setStatus(Const.OrderStatusEnum.CANCEL.getCode());
        int row = orderMapper.updateByPrimaryKeySelective(updateOrder);
        if (row > 0) {
            return ServerResponse.createBySuccess();
        }
        return ServerResponse.createByError();
    }

    public ServerResponse getOrderCartProduct(Integer userId) {
        OrderProductVO orderProductVO = new OrderProductVO();
        List<Cart> cartList = cartMapper.selectCheckedCartByUserId(userId);
        ServerResponse serverResponse = this.getCartOrderItem(userId, cartList);
        if (!serverResponse.isSuccess()) {
            return serverResponse;
        }
        List<OrderItem> orderItemList = (List<OrderItem>) serverResponse.getData();
        List<OrderItemVO> orderItemVOList = Lists.newArrayList();
        BigDecimal payment = new BigDecimal("0");
        for (OrderItem orderItem : orderItemList) {
            payment = BigDecimalUtil.add(payment.doubleValue(), orderItem.getTotalPrice().doubleValue());
            orderItemVOList.add(this.assembleOrderItemVO(orderItem));
        }
        orderProductVO.setProductTotalPrice(payment);
        orderProductVO.setOrderItemVOList(orderItemVOList);
        orderProductVO.setImageHost(PropertiesUtil.getProperty("ftp.server.http.prefix"));
        return ServerResponse.createBySuccess(orderProductVO);
    }

    public ServerResponse<OrderVO> getOrderDetail(Integer userId, Long orderNo){
        Order order = orderMapper.selectByUserIdAndOrderNo(userId, orderNo);
        if (order != null) {
            List<OrderItem> orderItemList = orderItemMapper.selectByOrderNoUserId(orderNo, userId);
            OrderVO orderVO = this.assembleOrderVO(order, orderItemList);
            return ServerResponse.createBySuccess(orderVO);
        }
        return ServerResponse.createByErrorMessage("该用户此订单不存在");
    }

    public ServerResponse<PageInfo> getOrderList(Integer userId, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Order> orderList = orderMapper.selectByUserId(userId);
        List<OrderVO> orderVOList = this.assembleOrderVOList(orderList, userId);
        PageInfo pageResult = new PageInfo(orderList);
        pageResult.setList(orderVOList);
        return ServerResponse.createBySuccess(pageResult);
    }
    private List<OrderVO> assembleOrderVOList(List<Order> orderList, Integer userId){
        List<OrderVO> orderVOList = Lists.newArrayList();
        for (Order order : orderList) {
            List<OrderItem> orderItemList = Lists.newArrayList();
            if (userId == null) {
                // 管理员，不需要传userId
                orderItemList = orderItemMapper.selectByOrderNo(order.getOrderNo());
            } else {
                orderItemList = orderItemMapper.selectByOrderNoUserId(order.getOrderNo(), userId);
            }
            OrderVO orderVO = this.assembleOrderVO(order, orderItemList);
            orderVOList.add(orderVO);
        }
        return orderVOList;
    }

    private OrderVO assembleOrderVO(Order order, List<OrderItem> orderItemList){
        OrderVO orderVO = new OrderVO();
        orderVO.setOrderNo(order.getOrderNo());
        orderVO.setPayment(order.getPayment());
        orderVO.setPaymentType(order.getPaymentType());
        orderVO.setPaymentTypeDesc(Const.PaymentTypeEnum.codeOf(order.getPaymentType()).getValue());
        orderVO.setPostage(order.getPostage());
        orderVO.setStatus(order.getStatus());
        orderVO.setStatusDesc(Const.OrderStatusEnum.codeOf(order.getStatus()).getValue());
        orderVO.setShippingId(order.getShippingId());
        Shipping shipping = shippingMapper.selectByPrimaryKey(order.getShippingId());
        if (shipping != null) {
            orderVO.setReceiverName(shipping.getReceiverName());
            orderVO.setShippingVO(this.assembleShippingVO(shipping));
        }
        orderVO.setPaymentTime(DateTimeUtil.dateToStr(order.getPaymentTime()));
        orderVO.setCloseTime(DateTimeUtil.dateToStr(order.getCloseTime()));
        orderVO.setCreateTime(DateTimeUtil.dateToStr(order.getCreateTime()));
        orderVO.setEndTime(DateTimeUtil.dateToStr(order.getEndTime()));
        orderVO.setSendTime(DateTimeUtil.dateToStr(order.getSendTime()));

        orderVO.setImageHost(PropertiesUtil.getProperty("ftp.server.http.prefix"));

        List<OrderItemVO> orderItemVOList = Lists.newArrayList();
        for (OrderItem orderItem : orderItemList) {
            orderItemVOList.add(this.assembleOrderItemVO(orderItem));
        }
        orderVO.setOrderItemVOList(orderItemVOList);
        return orderVO;

    }

    private OrderItemVO assembleOrderItemVO(OrderItem orderItem) {
        OrderItemVO orderItemVO = new OrderItemVO();
        orderItemVO.setCurrentUnitPrice(orderItem.getCurrentUnitPrice());
        orderItemVO.setOrderNo(orderItem.getOrderNo());
        orderItemVO.setProductId(orderItem.getProductId());
        orderItemVO.setProductName(orderItem.getProductName());
        orderItemVO.setProductImage(orderItem.getProductImage());
        orderItemVO.setTotalPrice(orderItem.getTotalPrice());
        orderItemVO.setCreateTime(DateTimeUtil.dateToStr(orderItem.getCreateTime()));
        orderItemVO.setQuantity(orderItem.getQuantity());
        return orderItemVO;
    }

    private ShippingVO assembleShippingVO(Shipping shipping) {
        ShippingVO shippingVO = new ShippingVO();
        shippingVO.setReceiverAddress(shipping.getReceiverAddress());
        shippingVO.setReceiverCity(shipping.getReceiverCity());
        shippingVO.setReceiverDistrict(shipping.getReceiverDistrict());
        shippingVO.setReceiverMobile(shipping.getReceiverMobile());
        shippingVO.setReceiverPhone(shipping.getReceiverPhone());
        shippingVO.setReceiverProvince(shipping.getReceiverProvince());
        shippingVO.setReceiverZip(shipping.getReceiverZip());
        shippingVO.setReceiverName(shipping.getReceiverName());
        return shippingVO;
    }


    private void cleanCart(List<Cart> cartList) {
        for (Cart cartItem : cartList) {
            cartMapper.deleteByPrimaryKey(cartItem.getId());
        }
    }

    private void reduceProductStock(List<OrderItem> orderItemList) {
        for (OrderItem orderItem : orderItemList) {
            Product product = productMapper.selectByPrimaryKey(orderItem.getProductId());
            product.setStock(product.getStock() - orderItem.getQuantity());
            productMapper.updateByPrimaryKeySelective(product);
        }
    }

    private Order assembleOrder(Integer userId, Integer shippingId, BigDecimal payment) {
        long orderNo = this.getOrderNo();
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setShippingId(shippingId);
        order.setPayment(payment);
        order.setStatus(Const.OrderStatusEnum.NO_PAY.getCode());
        order.setPostage(0);
        order.setPaymentType(Const.PaymentTypeEnum.ONLINE_PAY.getCode());

        int rowCount = orderMapper.insert(order);
        if (rowCount > 0) {
            return order;
        }
        return null;
    }

    private long getOrderNo() {
        // 要注意并发访问时防止两个订单产生同一个订单号
        long currentTime = System.currentTimeMillis();
        return currentTime + new Random().nextInt(100);
    }

    private BigDecimal getTotalPrice(List<OrderItem> orderItemList) {
        BigDecimal totalPrice = new BigDecimal("0");
        for (OrderItem orderItem : orderItemList) {
            totalPrice = BigDecimalUtil.add(totalPrice.doubleValue(), orderItem.getTotalPrice().doubleValue());
        }
        return totalPrice;
    }

    private ServerResponse getCartOrderItem(Integer userId, List<Cart> cartList) {
        List<OrderItem> orderItemList = Lists.newArrayList();
        if (CollectionUtils.isEmpty(cartList)) {
            return ServerResponse.createByErrorMessage("购物车为空");
        }
        // 校验购物车的数据，包括产品的数量和状态
        for (Cart cartItem : cartList) {
            OrderItem orderItem = new OrderItem();
            Product product = productMapper.selectByPrimaryKey(cartItem.getProductId());
            if (Const.ProductStatusEnum.ON_SALE.getCode() != product.getStatus()) {
                return ServerResponse.createByErrorMessage("产品不是在线售卖状态");
            }
            if (product.getStock() < cartItem.getQuantity()) {
                return ServerResponse.createByErrorMessage("库存不足");
            }
            orderItem.setProductId(product.getId());
            orderItem.setUserId(userId);
            orderItem.setProductName(product.getName());
            orderItem.setProductImage(product.getMainImage());
            orderItem.setCurrentUnitPrice(product.getPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setTotalPrice(BigDecimalUtil.mul(product.getPrice().doubleValue(), cartItem.getQuantity()));
            orderItemList.add(orderItem);
        }
        return ServerResponse.createBySuccess(orderItemList);
    }




    public ServerResponse pay(Long orderNo, Integer userId, String path) {
        Map<String, String> resultMap = Maps.newHashMap();
        Order order = orderMapper.selectByUserIdAndOrderNo(userId, orderNo);
        if (order == null) {
            return ServerResponse.createByErrorMessage("该用户没有该订单");
        }
        resultMap.put("orderNo", String.valueOf(order.getOrderNo()));


        // (必填) 商户网站订单系统中唯一订单号，64个字符以内，只能包含字母、数字、下划线，
        // 需保证商户系统端不能重复，建议通过数据库sequence生成，
        String outTradeNo = String.valueOf(order.getOrderNo());

        // (必填) 订单标题，粗略描述用户的支付目的。如“xxx品牌xxx门店当面付扫码消费”
        String subject = new StringBuilder().append("mall扫码支付，订单号：").append(outTradeNo).toString();

        // (必填) 订单总金额，单位为元，不能超过1亿元
        // 如果同时传入了【打折金额】,【不可打折金额】,【订单总金额】三者,则必须满足如下条件:【订单总金额】=【打折金额】+【不可打折金额】
        String totalAmount = String.valueOf(order.getPayment());

        // (可选) 订单不可打折金额，可以配合商家平台配置折扣活动，如果酒水不参与打折，则将对应金额填写至此字段
        // 如果该值未传入,但传入了【订单总金额】,【打折金额】,则该值默认为【订单总金额】-【打折金额】
        String undiscountableAmount = "0";

        // 卖家支付宝账号ID，用于支持一个签约账号下支持打款到不同的收款账号，(打款到sellerId对应的支付宝账号)
        // 如果该字段为空，则默认为与支付宝签约的商户的PID，也就是appid对应的PID
        String sellerId = "";

        // 订单描述，可以对交易或商品进行一个详细地描述，比如填写"购买商品2件共15.00元"
        String body = new StringBuilder().append("订单").append(outTradeNo).append("购买商品共").append(totalAmount).toString();

        // 商户操作员编号，添加此参数可以为商户操作员做销售统计
        String operatorId = "test_operator_id";

        // (必填) 商户门店编号，通过门店号和商家后台可以配置精准到门店的折扣信息，详询支付宝技术支持
        String storeId = "test_store_id";

        // 业务扩展参数，目前可添加由支付宝分配的系统商编号(通过setSysServiceProviderId方法)，详情请咨询支付宝技术支持
        ExtendParams extendParams = new ExtendParams();
        extendParams.setSysServiceProviderId("2088100200300400500");

        // 支付超时，定义为120分钟
        String timeoutExpress = "120m";

        // 商品明细列表，需填写购买商品详细信息，
        List<GoodsDetail> goodsDetailList = new ArrayList<GoodsDetail>();

        List<OrderItem> orderItemList = orderItemMapper.selectByOrderNoUserId(orderNo, userId);
        for (OrderItem orderItem : orderItemList) {
            // 创建一个商品信息，参数含义分别为商品id（使用国标）、名称、单价（单位为分）、数量，如果需要添加商品类别，详见GoodsDetail
            GoodsDetail goods1 = GoodsDetail.newInstance(String.valueOf(orderItem.getOrderNo()), orderItem.getProductName(),
                    BigDecimalUtil.mul(orderItem.getCurrentUnitPrice().doubleValue(), new Double(100).doubleValue()).longValue(),
                    orderItem.getQuantity());
            // 创建好一个商品后添加至商品明细列表
            goodsDetailList.add(goods1);
        }

        // 创建扫码支付请求builder，设置请求参数
        AlipayTradePrecreateRequestBuilder builder = new AlipayTradePrecreateRequestBuilder()
                .setSubject(subject).setTotalAmount(totalAmount).setOutTradeNo(outTradeNo)
                .setUndiscountableAmount(undiscountableAmount).setSellerId(sellerId).setBody(body)
                .setOperatorId(operatorId).setStoreId(storeId).setExtendParams(extendParams)
                .setTimeoutExpress(timeoutExpress)
                .setNotifyUrl(PropertiesUtil.getProperty("alipay.callback.url"))//支付宝服务器主动通知商户服务器里指定的页面http路径,根据需要设置
                .setGoodsDetailList(goodsDetailList);

        /** 一定要在创建AlipayTradeService之前调用Configs.init()设置默认参数
         *  Configs会读取classpath下的zfbinfo.properties文件配置信息，如果找不到该文件则确认该文件是否在classpath目录
         */
        Configs.init("zfbinfo.properties");

        /** 使用Configs提供的默认参数
         *  AlipayTradeService可以使用单例或者为静态成员对象，不需要反复new
         */
        AlipayTradeService tradeService = new AlipayTradeServiceImpl.ClientBuilder().build();


        AlipayF2FPrecreateResult result = tradeService.tradePrecreate(builder);
        switch (result.getTradeStatus()) {
            case SUCCESS:
                logger.info("支付宝预下单成功: )");

                AlipayTradePrecreateResponse response = result.getResponse();
                dumpResponse(response);

                File folder = new File(path);
                if (!folder.exists()) {
                    folder.setWritable(true);
                    folder.mkdirs();
                }
                // 需要修改为运行机器上的路径
                String qrPath = String.format(path + "/qr-%s.png", response.getOutTradeNo());
                String qrFileName = String.format("qr-%s.png", response.getOutTradeNo());
                ZxingUtils.getQRCodeImge(response.getQrCode(), 256, qrPath);

                File targetFile = new File(path, qrFileName);
                try {
                    FTPUtil.uploadFile(Lists.newArrayList(targetFile));
                } catch (IOException e) {
                    logger.error("上传二维码异常", e);
                    e.printStackTrace();
                }
                logger.info("qrPath:" + qrPath);
                String qrUrl = PropertiesUtil.getProperty("ftp.server.http.prefix") + targetFile.getName();
                resultMap.put("qrUrl", qrUrl);
                return ServerResponse.createBySuccess(resultMap);

            case FAILED:
                logger.error("支付宝预下单失败!!!");
                return ServerResponse.createByErrorMessage("支付宝预下单失败!!!");

            case UNKNOWN:
                logger.error("系统异常，预下单状态未知!!!");
                return ServerResponse.createByErrorMessage("系统异常，预下单状态未知!!!");

            default:
                logger.error("不支持的交易状态，交易返回异常!!!");
                return ServerResponse.createByErrorMessage("不支持的交易状态，交易返回异常!!!");
        }
    }
    // 简单打印应答
    private void dumpResponse(AlipayResponse response) {
        if (response != null) {
            logger.info(String.format("code:%s, msg:%s", response.getCode(), response.getMsg()));
            if (StringUtils.isNotEmpty(response.getSubCode())) {
                logger.info(String.format("subCode:%s, subMsg:%s", response.getSubCode(),
                        response.getSubMsg()));
            }
            logger.info("body:" + response.getBody());
        }
    }

    public ServerResponse aliCallback(Map<String, String> params) {
        Long orderNo = Long.parseLong(params.get("out_trade_no"));
        String tradeNo = params.get("trade_no");
        String tradeStatus = params.get("trade_status");
        System.out.println("tradeNo: " + tradeNo);
        System.out.println("tradeStatus: " + tradeStatus);
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return ServerResponse.createByErrorMessage("非此商城的订单，回调忽略");
        }
        if (order.getStatus() >= Const.OrderStatusEnum.PAID.getCode()) {
            return ServerResponse.createBySuccess("支付宝重复调用");
        }
        if (Const.AlipayCallback.TRADE_STATUS_TRADE_SUCCESS.equals(tradeStatus)) {
            System.out.println("进入更新订单");
            order.setPaymentTime(DateTimeUtil.strToDate(params.get("gmt_payment")));
            order.setStatus(Const.OrderStatusEnum.PAID.getCode());
            orderMapper.updateByPrimaryKeySelective(order);
        }
        PayInfo payInfo = new PayInfo();
        payInfo.setUserId(order.getUserId());
        payInfo.setOrderNo(order.getOrderNo());
        payInfo.setPayPlatform(Const.PayPlatformEnum.ALIPAY.getCode());
        payInfo.setPlatformNumber(tradeNo);
        payInfo.setPlatformStatus(tradeStatus);
        payInfoMapper.insert(payInfo);

        return ServerResponse.createBySuccess();
    }

    public ServerResponse queryOrderPayStatus(Integer userId, Long orderNo) {
        Order order = orderMapper.selectByUserIdAndOrderNo(userId, orderNo);
        if (order == null) {
            return ServerResponse.createByErrorMessage("用户没有该订单");
        }
        if (order.getStatus() >= Const.OrderStatusEnum.PAID.getCode()) {
            return ServerResponse.createBySuccess();
        }
        return ServerResponse.createByError();
    }


    // backend
    public ServerResponse<PageInfo> manageList(int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Order> orderList = orderMapper.selectAllOrder();
        List<OrderVO> orderVOList = this.assembleOrderVOList(orderList, null);
        PageInfo pageResult = new PageInfo(orderList);
        pageResult.setList(orderVOList);
        return ServerResponse.createBySuccess(pageResult);
    }

    public ServerResponse<OrderVO> manageDetail(Long orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order != null) {
            List<OrderItem> orderItemList = orderItemMapper.selectByOrderNo(orderNo);
            OrderVO orderVO = this.assembleOrderVO(order, orderItemList);
            return ServerResponse.createBySuccess(orderVO);
        }
        return ServerResponse.createByErrorMessage("订单不存在");
    }

    public ServerResponse<PageInfo> manageSearch(Long orderNo, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order != null) {
            List<OrderItem> orderItemList = orderItemMapper.selectByOrderNo(orderNo);
            OrderVO orderVO = this.assembleOrderVO(order, orderItemList);
            PageInfo pageResult = new PageInfo(Lists.newArrayList(order));
            pageResult.setList(Lists.newArrayList(orderVO));
            return ServerResponse.createBySuccess(pageResult);
        }
        return ServerResponse.createByErrorMessage("订单不存在");
    }

    public ServerResponse<String> manageSendGoods(Long orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order != null) {
            if (order.getStatus() == Const.OrderStatusEnum.PAID.getCode()) {
                order.setStatus(Const.OrderStatusEnum.SHIPPED.getCode());
                order.setSendTime(new Date());
                orderMapper.updateByPrimaryKeySelective(order);
                return ServerResponse.createBySuccess("发货成功");
            }
        }
        return ServerResponse.createByErrorMessage("订单不存在");
    }

}
